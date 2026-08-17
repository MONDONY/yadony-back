# language: fr
@regression @requests
Fonctionnalité: Demandes de colis et négociation
  En tant qu'expéditeur et voyageur
  Je veux publier des demandes de colis et négocier le prix
  Afin de convenir d'un transport sur mesure

  @happy-path @critical
  Scénario: Cycle complet de négociation jusqu'au paiement en attente
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-001" et le téléphone "+33688000001"
    Et le KYC de "req-sender-001" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-001" et le téléphone "+33688000002"
    Et le KYC de "req-traveler-001" est vérifié
    Etant donné l'utilisateur "req-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-1"
    Alors la réponse HTTP est 201
    Et le statut de la demande est "OPEN"
    Etant donné l'utilisateur "req-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je recherche les demandes de colis ouvertes
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 1 demande
    Quand je démarre une négociation sur la demande "demande-1" avec un prix de 85.0 € sauvegardée sous "nego-1"
    Alors la réponse HTTP est 201
    Et le statut de la négociation est "OPEN"
    Etant donné l'utilisateur "req-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je fais une contre-offre de 100.0 € sur la négociation "nego-1"
    Alors la réponse HTTP est 200
    Etant donné l'utilisateur "req-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je fais une contre-offre de 92.0 € sur la négociation "nego-1"
    Alors la réponse HTTP est 200
    Etant donné l'utilisateur "req-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand j'accepte le prix de la négociation "nego-1"
    Alors la réponse HTTP est 200
    # Le trajet dédié est déjà lié depuis le démarrage de la négociation (createDedicatedTrip
    # obligatoire dès start() — cf. spec 2026-08-16) : accept() saute directement à
    # AWAITING_PAYMENT, quel que soit l'accepteur.
    Et le statut de la négociation est "AWAITING_PAYMENT"
    Quand je complète les informations du destinataire de la demande "demande-1"
    Alors la réponse HTTP est 200
    Quand j'initie le paiement séquestre de la négociation "nego-1"
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "clientSecret"

  @happy-path
  Scénario: Acceptation immédiate puis refus et recréation d'un trajet dédié
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-002" et le téléphone "+33688000003"
    Et le KYC de "req-sender-002" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-002" et le téléphone "+33688000004"
    Et le KYC de "req-traveler-002" est vérifié
    Etant donné l'utilisateur "req-sender-002" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-2"
    Alors la réponse HTTP est 201
    Etant donné l'utilisateur "req-traveler-002" est authentifié en tant que VOYAGEUR
    Quand je démarre une négociation sur la demande "demande-2" avec un prix de 90.0 € sauvegardée sous "nego-2"
    Alors la réponse HTTP est 201
    Etant donné l'utilisateur "req-sender-002" est authentifié en tant qu'EXPÉDITEUR
    Quand j'accepte le prix de la négociation "nego-2"
    Alors la réponse HTTP est 200
    # Trajet dédié déjà lié dès start() → accept() saute directement à AWAITING_PAYMENT.
    Et le statut de la négociation est "AWAITING_PAYMENT"
    Quand je refuse le trajet de la négociation "nego-2"
    Alors la réponse HTTP est 200
    Et le statut de la négociation est "AWAITING_TRIP"
    Etant donné l'utilisateur "req-traveler-002" est authentifié en tant que VOYAGEUR
    Quand je crée un trajet dédié sur la négociation "nego-2"
    Alors la réponse HTTP est 200
    Et le statut de la négociation est "AWAITING_PAYMENT"

  @happy-path
  Scénario: Le voyageur refuse une négociation
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-003" et le téléphone "+33688000005"
    Et le KYC de "req-sender-003" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-003" et le téléphone "+33688000006"
    Et le KYC de "req-traveler-003" est vérifié
    Etant donné l'utilisateur "req-sender-003" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-3"
    Etant donné l'utilisateur "req-traveler-003" est authentifié en tant que VOYAGEUR
    Quand je démarre une négociation sur la demande "demande-3" avec un prix de 80.0 € sauvegardée sous "nego-3"
    Alors la réponse HTTP est 201
    Quand je refuse la négociation "nego-3"
    Alors la réponse HTTP est 204

  @error-case
  Scénario: Le prix d'une demande non négociable doit être respecté
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-004" et le téléphone "+33688000007"
    Et le KYC de "req-sender-004" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-004" et le téléphone "+33688000008"
    Et le KYC de "req-traveler-004" est vérifié
    Etant donné l'utilisateur "req-sender-004" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis non négociable avec un budget de 112.0 € sauvegardée sous "demande-4"
    Alors la réponse HTTP est 201
    Etant donné l'utilisateur "req-traveler-004" est authentifié en tant que VOYAGEUR
    Quand je démarre une négociation sur la demande "demande-4" avec un prix de 50.0 € sauvegardée sous "nego-4"
    Alors la réponse HTTP est 422

  @happy-path
  Scénario: L'expéditeur annule sa demande de colis
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-005" et le téléphone "+33688000009"
    Et le KYC de "req-sender-005" est vérifié
    Etant donné l'utilisateur "req-sender-005" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-5"
    Quand j'annule la demande de colis "demande-5"
    Alors la réponse HTTP est 204

  @happy-path
  Scénario: Consultation des demandes, du détail, des fils et estimation
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-006" et le téléphone "+33688000010"
    Et le KYC de "req-sender-006" est vérifié
    Etant donné l'utilisateur "req-sender-006" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-6"
    Quand je consulte mes demandes de colis
    Alors la réponse HTTP est 200
    Quand je consulte le détail de la demande "demande-6"
    Alors la réponse HTTP est 200
    Quand je consulte les fils de négociation de la demande "demande-6"
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 0 élément
    Quand j'estime le prix de "Paris" à "Dakar" pour 8.0 kg
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Recherche de demandes filtrée par ville de départ
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-007" et le téléphone "+33688000011"
    Et le KYC de "req-sender-007" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-007" et le téléphone "+33688000012"
    Et le KYC de "req-traveler-007" est vérifié
    Etant donné l'utilisateur "req-sender-007" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-7"
    Etant donné l'utilisateur "req-traveler-007" est authentifié en tant que VOYAGEUR
    Quand je recherche les demandes de colis au départ de "Paris"
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 1 demande
    Quand je consulte mes négociations
    Alors la réponse HTTP est 200

  @error-case
  Scénario: Un voyageur ne peut pas ouvrir deux négociations sur la même demande
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-008" et le téléphone "+33688000013"
    Et le KYC de "req-sender-008" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-008" et le téléphone "+33688000014"
    Et le KYC de "req-traveler-008" est vérifié
    Etant donné l'utilisateur "req-sender-008" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-8"
    Etant donné l'utilisateur "req-traveler-008" est authentifié en tant que VOYAGEUR
    Quand je démarre une négociation sur la demande "demande-8" avec un prix de 85.0 € sauvegardée sous "nego-8"
    Alors la réponse HTTP est 201
    Quand je consulte la négociation "nego-8"
    Alors la réponse HTTP est 200
    Quand je démarre une négociation sur la demande "demande-8" avec un prix de 70.0 € sauvegardée sous "nego-8-bis"
    Alors la réponse HTTP est 409

  @happy-path
  Scénario: L'expéditeur refuse le trajet soumis par le voyageur
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "req-sender-009" et le téléphone "+33688000015"
    Et le KYC de "req-sender-009" est vérifié
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "req-traveler-009" et le téléphone "+33688000016"
    Et le KYC de "req-traveler-009" est vérifié
    Etant donné l'utilisateur "req-sender-009" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une demande de colis négociable sauvegardée sous "demande-9"
    Etant donné l'utilisateur "req-traveler-009" est authentifié en tant que VOYAGEUR
    Quand je démarre une négociation sur la demande "demande-9" avec un prix de 88.0 € sauvegardée sous "nego-9"
    Alors la réponse HTTP est 201
    Etant donné l'utilisateur "req-sender-009" est authentifié en tant qu'EXPÉDITEUR
    Quand j'accepte le prix de la négociation "nego-9"
    Alors la réponse HTTP est 200
    # Trajet dédié déjà lié dès start() → accept() saute directement à AWAITING_PAYMENT.
    Et le statut de la négociation est "AWAITING_PAYMENT"
    Quand je refuse le trajet de la négociation "nego-9"
    Alors la réponse HTTP est 200
    Et le statut de la négociation est "AWAITING_TRIP"
    Etant donné l'utilisateur "req-traveler-009" est authentifié en tant que VOYAGEUR
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-nego-9"
    Quand je soumets le trajet "annonce-nego-9" sur la négociation "nego-9"
    Alors la réponse HTTP est 200
    Et le statut de la négociation est "AWAITING_PAYMENT"
    Etant donné l'utilisateur "req-sender-009" est authentifié en tant qu'EXPÉDITEUR
    Quand je refuse le trajet de la négociation "nego-9"
    Alors la réponse HTTP est 200
