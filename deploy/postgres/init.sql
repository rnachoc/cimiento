CREATE EXTENSION IF NOT EXISTS vector;

-- The agents service keeps its search index in its own database, never in the core's.
CREATE DATABASE cimiento_search;
\connect cimiento_search
CREATE EXTENSION IF NOT EXISTS vector;
