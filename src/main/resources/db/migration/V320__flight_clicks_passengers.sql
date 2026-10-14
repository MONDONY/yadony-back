-- Nombre total de voyageurs (bébés compris) choisi sur l'écran Billets au moment du clic.
-- Nul pour les clics des anciennes versions de l'app, qui ne l'envoient pas.
ALTER TABLE flight_clicks ADD COLUMN passengers SMALLINT;
