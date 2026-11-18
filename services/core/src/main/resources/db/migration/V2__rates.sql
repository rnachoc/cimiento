-- Constructora Brisa rates. Must match data/generator catalogue (checked by RatesMatchGeneratorTest).
INSERT INTO rate (code, description, activity, unit, unit_price, rate_per_day) VALUES
    ('MT01', 'Excavación en zanjas y pozos',        'A01', 'm3', 18.50,  60),
    ('MT02', 'Relleno y compactación de tierras',   'A01', 'm3', 12.30,  80),
    ('CI01', 'Hormigón de limpieza HL-150',         'A02', 'm3', 85.00,  25),
    ('CI02', 'Hormigón armado HA-25 en zapatas',    'A02', 'm3', 160.00, 15),
    ('ES01', 'Hormigón armado en pilares',          'A03', 'm3', 420.00, 6),
    ('ES02', 'Forjado reticular',                   'A03', 'm2', 65.00,  40),
    ('CE01', 'Fábrica de ladrillo cara vista',      'A04', 'm2', 38.00,  25),
    ('CU01', 'Cubierta plana invertida',            'A05', 'm2', 72.00,  30),
    ('CA01', 'Carpintería exterior de aluminio',    'A06', 'ud', 310.00, 8),
    ('IS01', 'Red de saneamiento enterrada',        'A07', 'm',  45.00,  40),
    ('IE01', 'Punto de luz completo',               'A08', 'ud', 55.00,  12),
    ('IF01', 'Punto de agua de fontanería',         'A09', 'ud', 95.00,  6),
    ('AC01', 'Solado de gres porcelánico',          'A10', 'm2', 32.00,  30),
    ('AC02', 'Pintura plástica en paramentos',      'A11', 'm2', 6.50,   120),
    ('UR01', 'Pavimento de urbanización exterior',  'A12', 'm2', 28.00,  70);
