-- Nom donné par l'inviteur à la personne invitée (FLUTTER-7V) : la liste « Invitations
-- envoyées » n'affichait qu'un numéro masqué. Chiffré (EncryptedStringConverter) : c'est
-- le libellé choisi par l'inviteur, il ne révèle pas l'existence d'un compte.
ALTER TABLE recipient_invitations ADD COLUMN label TEXT;
