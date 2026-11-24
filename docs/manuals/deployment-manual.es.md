# Cimiento: manual de montaje en una empresa

Versión de la aplicación: rama `main`, noviembre de 2026. Este manual explica cómo instalar Cimiento en un servidor de la empresa, configurarlo, protegerlo, hacer copias de seguridad y actualizarlo. Está pensado para la persona de sistemas que lo monta.

## 0. Antes de empezar: estado real

Cimiento es un proyecto de portfolio probado con **datos sintéticos**. `SECURITY.md` lo dice claramente: no está pensado para producción tal cual. Montarlo en una empresa real es razonable como **piloto controlado**, con personas reales revisando cada propuesta, que es justo como está diseñado.

Ya está listo:

- El núcleo con sus reglas, la auditoría encadenada por hash y la revisión por personas con roles y cuatro ojos.
- La subida de licitaciones desde la consola y un trabajador de agentes que las analiza.
- La consola web: bandeja de revisión, cartera, fichas, seguimiento de obra, certificaciones con PDF, calendario laboral y vista de dirección.
- Contenedores Docker, migraciones automáticas de base de datos, servidor MCP de solo lectura y observabilidad.

Hay que resolver antes de usarlo con licitaciones reales:

| Tema | Situación actual | Qué hacer |
|---|---|---|
| Lectura de documentos reales | El lector sin clave está escrito para el formato de los documentos sintéticos. | Con documentos reales hace falta un modelo (`ANTHROPIC_API_KEY`). La calidad con un modelo real **no está medida**: medirla con licitaciones propias antes de confiar. |
| Identidad | Tokens personales. Sin inicio de sesión corporativo (OIDC o SAML) ni bloqueo por intentos fallidos. | Aceptarlo para el piloto o integrar el proveedor de identidad. |
| Bajas de personas | Quitar a alguien de `CIMIENTO_PEOPLE` no lo desactiva. | Sustituir su `tokenHash` (apartado 5). |
| HTTPS | El proxy de la consola no reenvía `X-Forwarded-Proto`, así que la cookie de sesión sale sin la marca `Secure`. | Añadir una línea antes de construir la consola (apartado 8). |
| Tarifas | Vienen las de la empresa ficticia. | Cargar las propias (apartado 9). |
| Copias de seguridad | No incluidas. | Programarlas (apartado 12). |

## 1. Componentes

| Componente | Tecnología | Puerto interno | ¿Obligatorio? |
|---|---|---|---|
| Núcleo (`core`) | Java 17, Spring Boot 3 | 8080 | Sí |
| Consola (`console`) | Angular servido por nginx | 8080 (publicado en 8088) | Sí |
| Base de datos (`postgres`) | PostgreSQL 16 con pgvector | 5432 | Sí |
| Mensajería (`kafka`) | Apache Kafka 3.9 | 9092 | No (eventos) |
| Trabajador de agentes (`agents-worker`) | Python 3.12, contenedor | - | Sí, para analizar las licitaciones subidas |
| Búsqueda en documentos | Python, FastAPI | 8001 | No |
| Servidor MCP | Python, solo lectura | - | No |
| Observabilidad | OpenTelemetry, Jaeger, Prometheus, Grafana | varios | No |

El navegador solo habla con la consola. La consola añade la clave de revisor en el servidor y reenvía `/api/` al núcleo; el navegador nunca ve una clave. Cada persona entra con su token y viaja en una cookie de sesión `HttpOnly`.

## 2. Requisitos

- Un servidor Linux x86_64, por ejemplo Ubuntu Server 24.04 LTS.
- Docker Engine con Docker Compose 2.24.4 o posterior (el archivo del apartado 6 usa `!override`).
- Como orientación (no medido con carga real): 4 vCPU, 8 GB de RAM y 50 GB de disco.
- Un nombre de dominio interno o público, por ejemplo `cimiento.tuempresa.es`, y su certificado TLS.
- Para los agentes: Python 3.12 y [uv](https://docs.astral.sh/uv/).
- Si se usa un modelo de Claude: salida HTTPS hacia `api.anthropic.com` y una clave de API de la empresa.
- `git` y `openssl`.

## 3. Obtener el código

```bash
sudo mkdir -p /opt/cimiento && sudo chown "$USER" /opt/cimiento
git clone https://github.com/rnachoc/cimiento.git /opt/cimiento
cd /opt/cimiento
git checkout <commit-o-etiqueta-probado>
```

Fija siempre una versión concreta; no despliegues lo último de `main` sin probarlo.

## 4. Configuración (`.env`)

```bash
cp .env.example .env
chmod 600 .env
```

Genera un valor aleatorio distinto para cada secreto:

```bash
openssl rand -hex 32
```

| Variable | Para qué | Valor |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER` | Base de datos | `cimiento` (o el que elijas) |
| `POSTGRES_PASSWORD` | Contraseña de la base de datos | Aleatorio |
| `CIMIENTO_AGENT_KEY` | Clave de los agentes: solo pueden enviar propuestas | Aleatorio |
| `CIMIENTO_ANALYST_KEY` | Clave de solo lectura (MCP, informes) | Aleatorio |
| `CIMIENTO_REVIEWER_KEY` | Clave que usa la consola para decidir | Aleatorio |
| `CIMIENTO_PEOPLE` | Personas que deciden (apartado 5) | JSON en una línea |
| `CIMIENTO_OUTBOX_ENABLED` | Publicar la auditoría en Kafka | `false` salvo que uses Kafka |
| `CORE_PORT`, `CONSOLE_PORT` | Puertos publicados en el servidor | `8080` y `8088` |
| `ANTHROPIC_API_KEY` | Solo para los agentes, si usan Claude | Clave de la empresa o vacío |

Las tres claves de rol deben ser distintas. El núcleo **se niega a arrancar** si siguen con los valores de ejemplo de `.env.example`.

Parámetros del negocio (se fijan en el archivo del apartado 6):

| Variable | Por defecto | Qué es |
|---|---|---|
| `CIMIENTO_RETENTION_PCT` | 5 | Retención de cada certificación, en % |
| `CIMIENTO_VAT_PCT` | 21 | IVA de cada certificación, en % |
| `CIMIENTO_REVIEW_AMOUNTTHRESHOLDEUR` | 1000000 | Presupuestos desde este importe (sin IVA) solo los decide gerencia |

## 5. Personas y tokens

Cada persona tiene usuario, nombre, rol y un token personal. El núcleo solo guarda el **hash SHA-256** del token. Roles: `ESTIMATING` (estudios), `SITE_MANAGER` (jefatura de obra) y `MANAGEMENT` (gerencia). El manual de usuario explica qué puede hacer cada uno.

Para cada persona:

```bash
TOKEN=$(openssl rand -hex 24)
echo "Token para entregar a la persona: $TOKEN"
printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1   # este hash va a .env
```

Después, en `.env`, en una sola línea:

```bash
CIMIENTO_PEOPLE='[{"username":"mgarcia","displayName":"María García","role":"MANAGEMENT","tokenHash":"<hash>"},{"username":"lperez","displayName":"Luis Pérez","role":"ESTIMATING","tokenHash":"<hash>"},{"username":"aruiz","displayName":"Ana Ruiz","role":"SITE_MANAGER","tokenHash":"<hash>"}]'
```

- Entrega cada token por un canal seguro (en persona o con un gestor de contraseñas). No lo guardes en el servidor.
- Los cambios se aplican al reiniciar el núcleo (apartado 7).
- **Nuevo token** (pérdida o rotación): genera otro, sustituye su `tokenHash` y reinicia. El anterior deja de valer.
- **Baja**: quitar a la persona de la lista **no** la desactiva, porque el núcleo conserva su fila. Sustituye su `tokenHash` por el hash de un valor aleatorio que nadie conozca y reinicia.
- Las sesiones duran 8 horas.

## 6. Archivo de producción

`deploy/docker-compose.yml` publica todos los puertos en todas las interfaces, lo cual sirve en un portátil pero no en un servidor. Crea `deploy/docker-compose.prod.yml` (no lo subas a git) para publicar solo en local, reiniciar solo y fijar los parámetros:

```yaml
services:
  postgres:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:5432:5432"
  kafka:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:9092:9092"
  core:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:8080:8080"
    environment:
      CIMIENTO_RETENTION_PCT: "5"
      CIMIENTO_VAT_PCT: "21"
      CIMIENTO_REVIEW_AMOUNTTHRESHOLDEUR: "1000000"
  console:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:8088:8080"
  agents-worker:
    volumes: !override
      - /opt/cimiento-config/tarifas.json:/config/tarifas.json:ro
```

Desde fuera solo será accesible el proxy HTTPS del apartado 8.

## 7. Arranque

Para no repetir la orden, define un alias:

```bash
alias cim='docker compose -p cimiento -f /opt/cimiento/deploy/docker-compose.yml -f /opt/cimiento/deploy/docker-compose.prod.yml --env-file /opt/cimiento/.env --profile app --profile worker'
```

Primera vez (construye las imágenes; tarda varios minutos):

```bash
cim up -d --build
```

Comprobaciones:

```bash
cim ps                                         # todo "running" o "healthy"
curl -s http://127.0.0.1:8080/actuator/health  # {"status":"UP"}
curl -sI http://127.0.0.1:8088/ | head -1      # HTTP/1.1 200 OK
```

Las migraciones de base de datos se aplican solas al arrancar el núcleo.

Otras órdenes:

```bash
cim up -d core              # reiniciar el núcleo tras cambiar .env
cim logs -f core            # ver el registro del núcleo
cim stop                    # parar sin borrar datos
```

No uses `cim down -v`: la opción `-v` borra los volúmenes, es decir, la base de datos.

## 8. HTTPS y acceso desde la red

1. **Que la cookie de sesión sea segura.** Antes de construir la consola, edita `web/console/nginx/default.conf.template` y, dentro de `location /api/ { ... }`, añade:

   ```nginx
   proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
   ```

   Reconstruye: `cim up -d --build console`. El núcleo marca la cookie como `Secure` cuando la petición llega por HTTPS.

2. **Proxy HTTPS delante de la consola.** Por ejemplo con Caddy, que obtiene y renueva el certificado:

   ```
   cimiento.tuempresa.es {
       reverse_proxy 127.0.0.1:8088
   }
   ```

   Si usas otro proxy (nginx, Traefik, el balanceador de la empresa), envía `X-Forwarded-Proto: https` hacia la consola.

3. **Cortafuegos.** Abre solo 443 (y 80 si rediriges a 443). Ejemplo con ufw:

   ```bash
   sudo ufw default deny incoming
   sudo ufw allow OpenSSH
   sudo ufw allow 443/tcp
   sudo ufw enable
   ```

4. **Comprobación.** Entra en `https://cimiento.tuempresa.es`, inicia sesión y revisa en las herramientas del navegador que la cookie `cimiento_session` tiene `HttpOnly`, `Secure` y `SameSite=Strict`.

## 9. Tarifas de la empresa

Las tarifas (precio por unidad y rendimiento por día) alimentan el presupuesto y el planning. Las de serie son de la empresa ficticia.

Cada partida necesita: código, descripción, actividad, unidad, precio unitario (sin IVA) y rendimiento por día. Las actividades deben ser las que conoce el planificador, de `A01` (movimiento de tierras) a `A12` (urbanización). Añadir actividades nuevas requiere cambiar el planificador (`services/agents/src/cimiento_agents/planner.py`) y su tabla de dependencias.

Las tarifas viven en dos sitios, y deben coincidir:

1. **En el núcleo** (tabla `rate`). Para cargar o cambiar una partida:

   ```bash
   cim exec postgres psql -U cimiento -d cimiento -c "
     INSERT INTO rate (code, description, activity, unit, unit_price, rate_per_day)
     VALUES ('MT01', 'Excavación en zanjas y pozos', 'A01', 'm3', 19.20, 55)
     ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description, activity = EXCLUDED.activity,
       unit = EXCLUDED.unit, unit_price = EXCLUDED.unit_price, rate_per_day = EXCLUDED.rate_per_day;"
   ```

2. **Para los agentes**, en un `tarifas.json` con el mismo formato que `data/generated/eval/company/tarifas.json` (campos `codigo`, `descripcion`, `actividad`, `unidad`, `precio_unitario_eur`, `rendimiento_por_dia`).

## 10. Analizar licitaciones

Las personas de estudios y gerencia suben cada licitación desde la consola (**Cartera → + Nueva licitación**): código, título, inicio previsto y los PDF `pliego`, `anexo` y `mediciones`. El núcleo guarda los archivos y deja el trabajo en cola.

El contenedor `agents-worker` (perfil `worker`, incluido en el alias del apartado 7) toma los trabajos con la clave de agente, lee los documentos, envía las propuestas al núcleo como cualquier agente e informa del resultado. La consola muestra el estado y, al terminar, las propuestas esperan en la bandeja.

- **Tarifas:** el trabajador lee `/config/tarifas.json`. El archivo de producción del apartado 6 monta el de la empresa (`/opt/cimiento-config/tarifas.json`).
- **Modelo:** sin `ANTHROPIC_API_KEY` en `.env`, el trabajador usa el lector por reglas, sin coste. Con la clave usa Claude (`CIMIENTO_MODEL` elige el modelo) y el texto de los documentos se envía a la API de Anthropic. Revisa con la empresa si eso es aceptable.
- **Varios trabajadores:** se pueden arrancar más (`cim up -d --scale agents-worker=2`): el núcleo nunca entrega el mismo trabajo a dos. Un trabajo que lleva 15 minutos sin terminar se vuelve a ofrecer.
- **Registro:** `cim logs -f agents-worker`. El informe de cada trabajo solo lleva recuentos o el nombre del error, nunca texto de los documentos.

Para cargas masivas sigue existiendo el camino por script (`scripts/demo_run.py` como ejemplo) y el puente de Kafka `cimiento.intake`.

## 11. Opcionales

**Servidor MCP (consultas desde un asistente).** Solo lectura, con la clave de analista. Configuración de ejemplo en el `README.md` (apartado "Use it from an assistant").

**Kafka y eventos.** Para publicar la auditoría como eventos, pon `CIMIENTO_OUTBOX_ENABLED=true` y crea los temas, porque la creación automática está desactivada:

```bash
for t in cimiento.intake cimiento.intake.dlq cimiento.audit cimiento.audit.dlq; do
  cim exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 \
    --create --if-not-exists --topic "$t" --partitions 1 --replication-factor 1
done
```

**Observabilidad.** `docker compose ... --profile observability up -d` levanta el colector, Jaeger, Prometheus y Grafana. Grafana viene con acceso anónimo de lectura: desactívalo (`GF_AUTH_ANONYMOUS_ENABLED: "false"`) y no publiques sus puertos fuera del servidor. Las trazas solo llevan identificadores, recuentos y tiempos, nunca texto de los documentos.

## 12. Copias de seguridad

Toda la información está en PostgreSQL. Copia diaria:

```bash
mkdir -p /var/backups/cimiento
cim exec -T postgres pg_dump -U cimiento -Fc cimiento > /var/backups/cimiento/cimiento-$(date +%F).dump
```

Prográmala con `cron` y guarda las copias fuera del servidor. Guarda también `.env` en un lugar seguro y separado: sin él no se puede arrancar con los mismos secretos.

Restaurar en una base vacía:

```bash
cim exec -T postgres pg_restore -U cimiento -d cimiento --clean --if-exists < cimiento-AAAA-MM-DD.dump
```

Después de restaurar, comprueba la cadena de auditoría:

```bash
curl -s -H "X-Api-Key: $CIMIENTO_ANALYST_KEY" http://127.0.0.1:8080/api/audit/verify
```

Prueba una restauración al menos una vez antes de depender de las copias.

## 13. Actualizar a una versión nueva

1. Haz una copia de seguridad (apartado 12).
2. `git fetch && git checkout <nueva-versión>`.
3. Si modificaste `default.conf.template` (apartado 8), comprueba que el cambio sigue ahí.
4. `cim up -d --build`.
5. Repite las comprobaciones del apartado 7. Las migraciones nuevas se aplican solas; las certificaciones emitidas y la auditoría no se modifican.

## 14. Lista de comprobación final

- [ ] `.env` con secretos aleatorios, permisos 600 y copia guardada aparte.
- [ ] Personas reales en `CIMIENTO_PEOPLE` y tokens entregados por un canal seguro.
- [ ] Solo el puerto 443 abierto hacia fuera; la cookie sale con `Secure`.
- [ ] Tarifas propias cargadas en el núcleo y en `tarifas.json`.
- [ ] Copia de seguridad diaria programada y una restauración probada.
- [ ] `/api/audit/verify` responde que la cadena está intacta.
- [ ] Una licitación de prueba subida desde la consola, analizada por el trabajador y revisada por personas de cada rol.
- [ ] Decidido y documentado si se usa un modelo y qué datos salen de la empresa.

## 15. Problemas frecuentes

| Síntoma | Causa probable | Solución |
|---|---|---|
| El núcleo no arranca: `role keys still hold the .env.example placeholders` | Claves de ejemplo en `.env` | Generar claves aleatorias |
| El núcleo no arranca: `CIMIENTO_PEOPLE: tokenHash of ...` | Hash mal copiado | Debe ser el SHA-256 en hexadecimal (64 caracteres) |
| La consola dice que la sesión ha caducado | Pasaron 8 horas o se reinició con otro token | Volver a entrar |
| Un botón está desactivado o la acción da 403 | El rol de la persona no lo permite | Ver la tabla de roles del manual de usuario |
| Una subida se queda "En cola" | El trabajador no está arrancado | `cim ps` y `cim logs agents-worker` |
| Una subida acaba en "Error" | El trabajador no pudo leer o analizar los PDF | Ver el nombre del error en la consola y `cim logs agents-worker` |
| La subida falla con un archivo grande | Más de 20 MB por archivo o 64 MB en total | Reducir el PDF |
| Datos "no consta" en licitaciones reales | El lector sin clave no entiende ese formato | Usar un modelo y revisar la calidad con documentos propios |
