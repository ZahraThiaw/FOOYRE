package com.fooyre.architecture.fixtures.sales.application;

import com.fooyre.architecture.fixtures.stock.domain.StockItem;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Classe FAUTIVE de test (jamais chargée par Spring : aucune annotation de composant) :
 * chaque élément viole volontairement une règle d'architecture.
 */
public class BadSalesService {

    /** Violation : accès au domaine d'un autre module. */
    StockItem otherModuleDomain;

    /** Violation : montant en double. */
    double total;

    /** Violation : injection par champ. */
    @Autowired
    Object injected;

    /** Violation : float dans une signature. */
    float rate(float value) {
        return value;
    }

    /** Violation : BigDecimal construit depuis un double. */
    BigDecimal amount() {
        return new BigDecimal(0.1d);
    }
}
