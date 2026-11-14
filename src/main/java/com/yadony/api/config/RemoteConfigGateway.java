package com.yadony.api.config;

import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.Template;

/**
 * Fine couche autour du SDK Firebase Remote Config : les tests injectent un faux, aucun appel
 * reel a Firebase n'y est fait.
 *
 * <p>{@link #publish(Template)} republie le template TEL QUE LU (son ETag voyage avec lui) :
 * si quelqu'un l'a modifie entre-temps, Firebase refuse (HTTP 412) et rien n'est ecrase.
 */
public interface RemoteConfigGateway {

    /** Template publie courant, avec son ETag. */
    Template fetch() throws FirebaseRemoteConfigException;

    /** Publie le template (If-Match sur l'ETag lu) et renvoie la version publiee. */
    Template publish(Template template) throws FirebaseRemoteConfigException;
}
