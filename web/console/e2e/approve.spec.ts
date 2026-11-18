import { expect, request, test } from '@playwright/test';

// The core is called directly with role keys to seed and verify; the browser only talks to
// nginx, which adds the REVIEWER key itself.
const CORE = process.env['CORE_URL'] ?? 'http://localhost:8080';
const key = (name: string) => {
  const v = process.env[name];
  if (!v) throw new Error(`${name} must be set`);
  return v;
};

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
  await expect(page.getByTestId(`card-${proposal.id}`)).toBeVisible();
  await page.getByTestId(`card-${proposal.id}`).click();
  await expect(page.getByTestId('status')).toHaveText('Pendiente de revisión');
  await expect(page.locator('.g-bar')).toHaveCount(2);
  await page.getByTestId('approve').click();
  await expect(page.getByTestId('status')).toHaveText('Ejecutada');

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

  // the pipeline: the reviewer moves the tender, and the core refuses to skip its rules
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
