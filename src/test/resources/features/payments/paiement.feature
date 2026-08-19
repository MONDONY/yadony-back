# language: fr
@regression @payments
Fonctionnalité: Gestion des paiements en escrow
  En tant qu'expéditeur ou voyageur
  Je veux gérer les paiements sécurisés liés au transport
  Afin de protéger les deux parties jusqu'à la livraison confirmée

  @happy-path
  Scénario: Consultation du statut de paiement d'une offre sans paiement
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-pay-001" et le téléphone "+33699000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-pay-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-pay-001" et le téléphone "+33699000002"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-pay-1"
    Et l'offre "offre-pay-1" est sauvegardée
    Quand je demande le statut de paiement de l'offre "offre-pay-1"
    Alors la réponse HTTP est 404

  @error-case
  Scénario: Tentative de paiement pour une offre annulée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-pay-002" et le téléphone "+33699000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-pay-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-pay-002" et le téléphone "+33699000004"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-pay-2"
    Et l'offre "offre-pay-2" est sauvegardée
    Quand l'expéditeur annule l'offre "offre-pay-2"
    Et je tente de créer un paiement pour l'offre "offre-pay-2"
    Alors la réponse HTTP est 422

  @happy-path @critical
  Scénario: Création d'un paiement séquestre pour une offre payable
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-pay-003" et le téléphone "+33699000005"
    Et le compte Stripe du voyageur "traveler-pay-003" est opérationnel
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-pay-3"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-pay-003" et le téléphone "+33699000006"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-pay-3"
    Et l'offre "offre-pay-3" est sauvegardée
    Quand je tente de créer un paiement pour l'offre "offre-pay-3"
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "clientSecret"
    Quand je demande le statut de paiement de l'offre "offre-pay-3"
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Onboarding Stripe Connect — création, lien et rafraîchissement
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "connect-001" et le téléphone "+33699000007"
    Et le voyageur "connect-001" n'a pas encore de compte Stripe
    Et le voyageur "connect-001" a pour pays "FR"
    Etant donné l'utilisateur "connect-001" est authentifié en tant que VOYAGEUR
    Quand je consulte le statut de mon compte Stripe Connect
    Alors la réponse HTTP est 200
    Quand je crée mon compte Stripe Connect
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "stripeAccountId"
    Quand je génère mon lien d'onboarding Stripe
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "url"
    Quand je rafraîchis mon compte Stripe Connect
    Alors la réponse HTTP est 200

  @error-case
  Scénario: Webhook Stripe avec signature invalide
    Etant donné aucun utilisateur n'est authentifié
    Quand j'envoie un webhook Stripe avec une signature invalide
    Alors la réponse HTTP est 400
    Et le code d'erreur de la réponse est "invalid-webhook-signature"

  @critical @idempotence
  Scénario: Rejeu d'un webhook Stripe d'escrow — ingéré une seule fois
    # Stripe rejoue ses webhooks avec le MÊME event id. La garde d'idempotence
    # (StripeWebhookIngestService : insert-first puis dédoublonnage par event id)
    # doit ignorer le doublon sans erreur — sinon risque de double traitement
    # (double activation d'escrow, double capture…).
    Etant donné aucun utilisateur n'est authentifié
    Quand le webhook Stripe de type "payment_intent.amount_capturable_updated" et d'identifiant "evt_e2e_escrow_replay" est reçu
    Alors la réponse HTTP est 200
    Et l'évènement Stripe "evt_e2e_escrow_replay" a été ingéré
    Quand le webhook Stripe de type "payment_intent.amount_capturable_updated" et d'identifiant "evt_e2e_escrow_replay" est reçu
    Alors la réponse HTTP est 200
    Et l'inbox Stripe contient 1 évènement
