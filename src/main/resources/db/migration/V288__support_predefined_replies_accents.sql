-- V288 : accents des questions fréquentes du support (FLUTTER-A0).
--
-- V244 a inséré le français sans aucun accent (« Pourquoi mon compte doit-il
-- etre verifie ? »), affiché tel quel dans l'écran Support de l'app. On
-- réécrit question et answer par code ; les traductions anglaises (V265) ne
-- bougent pas, sauf le chemin de suppression du compte, qui a changé : il
-- passe désormais par le menu de l'onglet Moi.

UPDATE support_predefined_replies SET
    question = 'Pourquoi mon compte doit-il être vérifié ?',
    answer = 'La vérification d''identité protège les deux parties : elle garantit que la personne à qui vous confiez un colis, ou qui vous en confie un, est bien celle qu''elle prétend être. Elle prend quelques minutes et n''est demandée qu''une seule fois.'
WHERE code = 'account-verification';

UPDATE support_predefined_replies SET
    question = 'Ma vérification d''identité a échoué, que faire ?',
    answer = 'Reprenez la vérification depuis votre profil, en veillant à photographier un document en cours de validité, à plat, entièrement dans le cadre et sans reflet. Si l''échec se répète, ouvrez un ticket : nous vérifierons manuellement.'
WHERE code = 'kyc-rejected';

UPDATE support_predefined_replies SET
    question = 'À quel moment suis-je débité ?',
    answer = 'Vous êtes débité quand le voyageur accepte votre offre. La somme reste bloquée chez notre prestataire de paiement : le voyageur n''est payé qu''une fois la livraison confirmée par le destinataire.'
WHERE code = 'payment-when-charged';

UPDATE support_predefined_replies SET
    question = 'Quand arrive mon remboursement ?',
    answer = 'Un remboursement est renvoyé sur le moyen de paiement d''origine et apparaît en général sous 5 à 10 jours ouvrés, selon votre banque.'
WHERE code = 'payment-refund-delay';

UPDATE support_predefined_replies SET
    question = 'Je suis voyageur, quand suis-je payé ?',
    answer = 'Le virement part dès que la livraison est confirmée par le destinataire, puis suit les délais bancaires habituels. Si personne ne confirme, le versement est déclenché automatiquement 48 heures après la date de livraison prévue.'
WHERE code = 'payout-delay';

UPDATE support_predefined_replies SET
    question = 'Comment se passe la remise du colis ?',
    answer = 'À la remise comme à la livraison, un QR code est scanné par les deux parties. Ce scan horodate l''échange et sert de preuve en cas de litige : ne remettez jamais un colis sans scanner.'
WHERE code = 'delivery-qr-scan';

UPDATE support_predefined_replies SET
    question = 'Mon colis n''est pas arrivé à la date prévue.',
    answer = 'Contactez d''abord le voyageur depuis la conversation liée au colis : un vol décalé ou un contrôle douanier explique la plupart des retards. Sans réponse sous 48 heures, ouvrez un ticket, nous interviendrons.'
WHERE code = 'delivery-late';

UPDATE support_predefined_replies SET
    question = 'Le voyageur a annulé son trajet, que se passe-t-il ?',
    answer = 'Vous êtes remboursé intégralement et nous vous proposons automatiquement d''autres voyageurs sur le même trajet.'
WHERE code = 'trip-cancelled';

UPDATE support_predefined_replies SET
    question = 'Que puis-je envoyer ?',
    answer = 'Sont interdits : espèces, bijoux et objets de valeur, médicaments sur ordonnance, produits périssables, batteries au lithium hors appareil, armes et tout produit illégal. La valeur déclarée d''un colis est plafonnée à 500 euros.'
WHERE code = 'package-forbidden-items';

UPDATE support_predefined_replies SET
    question = 'Comment supprimer mon compte ?',
    answer = 'Depuis l''onglet Moi, ouvrez le menu en haut à droite, puis Supprimer mon compte. La suppression est définitive une fois vos colis et trajets en cours terminés.',
    answer_en = 'From the Profile tab, open the menu at the top right, then Delete my account. The deletion is permanent once your ongoing parcels and trips are finished.'
WHERE code = 'account-delete';
