package com.yadony.api.admin.broadcast.rule;

/** Collecte les valeurs d'une regle : elles ne sont jamais concatenees dans le SQL. */
public interface ParamSink {

    /** Enregistre la valeur et renvoie son marqueur nomme (":p0", ":p1"...). */
    String bind(Object value);
}
