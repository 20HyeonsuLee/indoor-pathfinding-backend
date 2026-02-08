-- Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Enable pgRouting extension
CREATE EXTENSION IF NOT EXISTS pgrouting;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE indoor_pathfinding TO indoor;

-- Create schema for indoor pathfinding
CREATE SCHEMA IF NOT EXISTS indoor;
GRANT ALL ON SCHEMA indoor TO indoor;
