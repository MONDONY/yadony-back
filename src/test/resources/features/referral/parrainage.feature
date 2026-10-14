# language: fr
@regression @referral
Fonctionnalité: Programme de parrainage
  En tant qu'utilisateur
  Je veux disposer d'un code de parrainage et utiliser celui d'un ami
  Afin de bénéficier des récompenses de parrainage

  @happy-path
  Scénario: Consultation de mon code de parrainage
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ref-a-001" et le téléphone "+33699300001"
    Etant donné l'utilisateur "ref-a-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte mon code de parrainage
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "code"
    Et la réponse contient le champ "shareUrl"

  @happy-path
  Scénario: Un nouvel utilisateur utilise un code de parrainage
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ref-parrain-001" et le téléphone "+33699300002"
    Etant donné l'utilisateur "ref-parrain-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte mon code de parrainage
    Alors la réponse HTTP est 200
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ref-filleul-001" et le téléphone "+33699300003"
    Etant donné l'utilisateur "ref-filleul-001" est authentifié en tant qu'EXPÉDITEUR
    Quand j'utilise le code de parrainage sauvegardé
    Alors la réponse HTTP est 204

  @happy-path
  Scénario: Le parrain voit son filleul inscrit, sans récompense avant le premier envoi
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ref-parrain-002" et le téléphone "+33699300004"
    Etant donné l'utilisateur "ref-parrain-002" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte mon code de parrainage
    Alors la réponse HTTP est 200
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ref-filleul-002" et le téléphone "+33699300005"
    Etant donné l'utilisateur "ref-filleul-002" est authentifié en tant qu'EXPÉDITEUR
    Quand j'utilise le code de parrainage sauvegardé
    Alors la réponse HTTP est 204
    Etant donné l'utilisateur "ref-parrain-002" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte mes filleuls
    Alors la réponse HTTP est 200
    Et le champ "content[0].status" de la réponse vaut "SIGNED_UP"
    Et le champ "content[0].rewardStatus" de la réponse vaut "NONE"
    Quand je consulte mon code de parrainage
    Alors la réponse HTTP est 200
    Et le champ "totalInvited" de la réponse vaut "1"
    Et le champ "signedUp" de la réponse vaut "1"
    Et le champ "pendingRewardCount" de la réponse vaut "0"
