# language: fr
@regression @bids
Fonctionnalité: Cycle de vie d'une offre
  En tant que voyageur ou expéditeur
  Je veux gérer le cycle complet d'une offre
  Depuis l'acceptation jusqu'à la confirmation de livraison

  @happy-path @critical
  Scénario: Voyageur accepte une offre — numéro de suivi généré
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-lc-001" et le téléphone "+33677000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-lc-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-lc-001" et le téléphone "+33677000002"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-lc-1"
    Et l'offre "offre-lc-1" est sauvegardée
    Etant donné l'utilisateur "traveler-lc-001" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-lc-1" est validé
    Quand j'accepte l'offre "offre-lc-1"
    Alors la réponse HTTP est 200
    Et le statut de l'offre est "ACCEPTED"
    Et l'offre a un numéro de suivi
    Et le numéro de suivi n'est pas communiqué au voyageur

  @happy-path
  Scénario: Voyageur refuse une offre
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-lc-002" et le téléphone "+33677000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-lc-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-lc-002" et le téléphone "+33677000004"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-lc-2"
    Et l'offre "offre-lc-2" est sauvegardée
    Et le paiement de l'offre "offre-lc-2" est validé
    Etant donné l'utilisateur "traveler-lc-002" est authentifié en tant que VOYAGEUR
    Quand je refuse l'offre "offre-lc-2" avec la raison "Poids trop élevé pour mon trajet"
    Alors la réponse HTTP est 200
    Et le statut de l'offre est "REJECTED"

  @happy-path
  Scénario: Expéditeur annule une offre en attente
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-lc-003" et le téléphone "+33677000005"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-lc-3"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-lc-003" et le téléphone "+33677000006"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-lc-3"
    Et l'offre "offre-lc-3" est sauvegardée
    Quand l'expéditeur annule l'offre "offre-lc-3"
    Alors la réponse HTTP est 200
    Et le statut de l'offre est "CANCELLED"

  @happy-path
  Scénario: Consultation des offres en tant qu'expéditeur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-lc-004" et le téléphone "+33677000007"
    Et il existe une annonce de "Lyon" à "Douala" avec 30 kg disponibles à 4.0 €/kg sauvegardée sous "annonce-lc-4"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-lc-004" et le téléphone "+33677000008"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-lc-4"
    Quand je consulte mes offres en tant qu'expéditeur
    Alors la réponse HTTP est 200
    Et la réponse contient 1 offres

  @happy-path
  Scénario: Voyageur consulte les offres sur son annonce
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-lc-005" et le téléphone "+33677000009"
    Et il existe une annonce de "Marseille" à "Dakar" avec 25 kg disponibles à 5.5 €/kg sauvegardée sous "annonce-lc-5"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-lc-005" et le téléphone "+33677000010"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-lc-5"
    Etant donné l'utilisateur "traveler-lc-005" est authentifié en tant que VOYAGEUR
    Quand je consulte les offres de l'annonce "annonce-lc-5"
    Alors la réponse HTTP est 200
    Et la réponse contient 1 offres

