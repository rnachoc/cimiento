import { expect, request, test } from '@playwright/test';

// The core is called directly with role keys to seed and verify; the browser only talks to
// nginx, which adds the REVIEWER key itself.
const CORE = process.env['CORE_URL'] ?? 'http://localhost:8080';
const key = (name: string) => {
  const v = process.env[name];
  if (!v) throw new Error(`${name} must be set`);
  return v;
};

/** Signs in through the console form, as a person would. */
async function signIn(page: import('@playwright/test').Page, user: string, token: string) {
  await page.goto('/acceso');
  await page.getByLabel('Usuario').fill(user);
  await page.getByLabel('Token personal').fill(token);
  await page.getByTestId('sign-in').click();
  await expect(page.getByTestId('who')).toBeVisible();
}

test('a reviewer approves a schedule in a real browser and the core executes it', async ({ page }) => {
  const reviewer = await request.newContext({ baseURL: CORE, extraHTTPHeaders: { 'X-Api-Key': key('CIMIENTO_REVIEWER_KEY') } });
  const agent = await request.newContext({ baseURL: CORE, extraHTTPHeaders: { 'X-Api-Key': key('CIMIENTO_AGENT_KEY') } });
  const analyst = await request.newContext({ baseURL: CORE, extraHTTPHeaders: { 'X-Api-Key': key('CIMIENTO_ANALYST_KEY') } });

  await reviewer.post('/api/projects', { data: { code: 'E2E', name: 'End to end' } });
  const code = `E2E-${Date.now()}`;
  const tender = await (await agent.post('/api/tenders', {
    data: { projectCode: 'E2E', code, title: 'Licitación de prueba', contractTermMonths: 6, plannedStart: '2027-01-04' },
  })).json();
  const created = await agent.post('/api/proposals', {
    headers: { 'Idempotency-Key': code },
    data: {
      tenderId: tender.id, type: 'SCHEDULE',
      citations: [{ document: 'mediciones', page: 1, text: 'MT01 | Excavación' }],
      payload: {
        activities: [{ code: 'A01', name: 'Movimiento de tierras', duration: 5 },
                     { code: 'A02', name: 'Cimentación', duration: 8 }],
        links: [{ from: 'A01', to: 'A02', lag: 0 }],
      },
    },
  });
  expect(created.status()).toBe(201);
  const proposal = await created.json();
  expect(proposal.status).toBe('PENDING_REVIEW');

  const cspErrors: string[] = [];
  page.on('console', (m) => { if (/Content Security Policy|Refused to/i.test(m.text())) cspErrors.push(m.text()); });

  const response = await page.goto('/');
  expect(response?.headers()['content-security-policy']).toContain("script-src 'self'");
  // without a session every view sends you to the sign-in page
  await expect(page).toHaveURL(/\/acceso/);

  // four eyes: the site manager corrects the schedule, and cannot approve their own correction
  await signIn(page, 'obra', key('OBRA_TOKEN'));
  await expect(page.getByTestId('who')).toContainText('Jefatura de obra');
  await expect(page.getByTestId(`card-${proposal.id}`)).toBeVisible();
  await page.getByTestId(`card-${proposal.id}`).click();
  await expect(page.getByTestId('status')).toHaveText('Pendiente de revisión');
  await expect(page.locator('.g-bar')).toHaveCount(2);
  await page.getByTestId('correct').click();
  await page.getByTestId('correct').click();
  await expect(page.getByTestId('four-eyes')).toContainText('obra');
  await expect(page.getByTestId('approve')).toBeDisabled();
  await expect(page.getByTestId('status')).toHaveText('Pendiente de revisión');

  // management approves it: now it runs, and the audit names both people
  await page.getByRole('button', { name: 'Salir' }).click();
  await signIn(page, 'gerencia', key('GERENCIA_TOKEN'));
  await page.goto(`/proposals/${proposal.id}`);
  await page.getByTestId('approve').click();
  await expect(page.getByTestId('status')).toHaveText('Ejecutada');
  const audit = await (await analyst.get('/api/audit')).json();
  const people = audit.filter((a: { entityId: string; action: string }) => a.entityId === String(proposal.id)
    && a.action === 'DECISION_RECORDED').map((a: { actorPerson: string }) => a.actorPerson);
  expect(people).toEqual(['obra', 'gerencia']);

  const after = await (await analyst.get(`/api/proposals/${proposal.id}`)).json();
  expect(after.status).toBe('EXECUTED');
  const schedule = await (await analyst.get(`/api/tenders/${tender.id}/schedule`)).json();
  expect(schedule.durationDays).toBe(13);

  // the approved schedule downloads as MS Project XML through nginx
  await page.getByTestId('export').waitFor();
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('export').click()]);
  expect(download.suggestedFilename()).toBe(`planning-${code}-${proposal.id}.xml`);
  const xml = await (await import('node:fs/promises')).readFile(await download.path(), 'utf8');
  expect(xml).toContain('<Name>A02 Cimentación</Name>');

  // the pipeline: management moves the tender, and the core refuses to skip its rules
  await page.goto('/cartera');
  const card = page.getByTestId(`tender-${code}`);
  await expect(page.getByTestId('col-TO_REVIEW').getByTestId(`tender-${code}`)).toBeVisible();
  await card.getByTestId(`move-${code}-UNDER_REVIEW`).click();
  await expect(page.getByTestId('col-UNDER_REVIEW').getByTestId(`tender-${code}`)).toBeVisible();
  const stages = await (await analyst.get(`/api/tenders/${tender.id}/stages`)).json();
  expect(stages.map((s: { to: string }) => s.to)).toEqual(['UNDER_REVIEW']);

  // the tender file gathers everything about the tender; the management view loads
  await page.getByTestId(`open-${code}`).click();
  await expect(page.getByTestId('tender-title')).toContainText(code);
  await expect(page.getByTestId('stage')).toHaveText('En estudio');
  await expect(page.locator('.g-bar')).toHaveCount(2);
  await expect(page.getByTestId('history').locator('li')).toHaveCount(2);
  await page.goto('/direccion');
  await expect(page.getByTestId('funnel').locator('.b-row')).toHaveCount(8);
  expect(cspErrors).toEqual([]);
});

test('the browser cannot act as another role: nginx overwrites any key it sends', async ({ page }) => {
  await page.goto('/');
  const status = await page.evaluate(async () => {
    const r = await fetch('/api/proposals', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Api-Key': 'forged-agent-key-0123456789' },
      body: JSON.stringify({}),
    });
    return r.status;
  });
  expect(status).toBe(403); // reaches the core as REVIEWER, which may not propose
});

test('a site manager issues the monthly certification the core calculated', async ({ page }) => {
  const reviewer = await request.newContext({ baseURL: CORE, extraHTTPHeaders: { 'X-Api-Key': key('CIMIENTO_REVIEWER_KEY') } });
  const agent = await request.newContext({ baseURL: CORE, extraHTTPHeaders: { 'X-Api-Key': key('CIMIENTO_AGENT_KEY') } });
  const analyst = await request.newContext({ baseURL: CORE, extraHTTPHeaders: { 'X-Api-Key': key('CIMIENTO_ANALYST_KEY') } });

  await reviewer.post('/api/projects', { data: { code: 'E2E', name: 'End to end' } });
  const code = `CERT-${Date.now()}`;
  const tender = await (await agent.post('/api/tenders', {
    data: { projectCode: 'E2E', code, title: 'Certificación de prueba', contractTermMonths: 6, plannedStart: '2027-01-04' },
  })).json();
  const rates: { code: string; activity: string; unitPrice: string }[] = await (await analyst.get('/api/rates')).json();
  const rate = rates.find((r) => r.activity === 'A01')!;
  const total = (Number(rate.unitPrice) * 10).toFixed(2);
  const budget = await (await agent.post('/api/proposals', {
    headers: { 'Idempotency-Key': `${code}-b` },
    data: {
      tenderId: tender.id, type: 'BUDGET', citations: [{ document: 'mediciones', page: 1, text: 'x' }],
      payload: { lines: [{ code: rate.code, quantity: 10 }], claimedTotal: total },
    },
  })).json();
  // week 4 closes on 29 January: A01 is at 60 %, on plan, so the progress needs no review
  const progress = await agent.post('/api/proposals', {
    headers: { 'Idempotency-Key': `${code}-p` },
    data: {
      tenderId: tender.id, type: 'PROGRESS_UPDATE', citations: [{ document: 'obra/avance_semanal.csv', page: 1, text: 'x' }],
      payload: { week: 4, rows: [{ activity: 'A01', plannedPct: 60, actualPct: 60 }],
                 history: [{ week: 1, rows: [{ activity: 'A01', plannedPct: 20, actualPct: 20 }] }] },
    },
  });
  expect((await progress.json()).status).toBe('EXECUTED');
  // management approves the budget through the API with a session, like the console would
  expect((await reviewer.post('/api/session', { data: { username: 'gerencia', token: key('GERENCIA_TOKEN') } })).ok()).toBe(true);
  expect((await reviewer.post(`/api/proposals/${budget.id}/decision`, { data: { outcome: 'APPROVE' } })).ok()).toBe(true);

  // estimating sees the valuation but cannot issue it
  await signIn(page, 'estudios', key('ESTUDIOS_TOKEN'));
  await page.goto(`/certificaciones/${tender.id}`);
  await expect(page.getByTestId('period')).toBeVisible();
  await expect(page.getByTestId('issue')).toBeDisabled();
  await expect(page.getByTestId('why-not')).toContainText('Estudios');
  await page.getByRole('button', { name: 'Salir' }).click();

  // the site manager issues it; the amount comes from the core, not from the page
  await signIn(page, 'obra', key('OBRA_TOKEN'));
  await page.goto(`/certificaciones/${tender.id}`);
  await expect(page.getByTestId('issue')).toBeEnabled();
  await page.getByTestId('issue').click();
  await expect(page.getByTestId('issued').locator('tbody tr')).toHaveCount(1);
  await expect(page.getByTestId('issued')).toContainText('2027-01');
  await expect(page.getByTestId('issued')).toContainText('obra');
  const issued = await (await analyst.get(`/api/tenders/${tender.id}/certifications`)).json();
  expect(issued).toHaveLength(1);
  expect(issued[0].toDate).toBeCloseTo(Number(total) * 0.6, 2);
  // issuing the same month again is refused by the core and the button says why
  await expect(page.getByTestId('issue')).toBeDisabled();
});
