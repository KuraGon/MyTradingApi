package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Position client publiée, commune aux lignes du dashboard et à sa synthèse.
 * @param asset métal valorisé
 * @param quantityOz quantité signée canonique
 * @param clientPrice prix de liquidation client par once
 * @param valuation valeur signée publiée à deux décimales
 * @param priceAsOf instant de la cotation utilisée
 * @param marginRatePct taux de marge configuré exprimé en pourcentage
 * @param marginRequirement marge requise publiée à deux décimales
 */
public record AccountPosition(Asset asset, BigDecimal quantityOz, BigDecimal clientPrice,
                              BigDecimal valuation, Instant priceAsOf,
                              BigDecimal marginRatePct, BigDecimal marginRequirement) {}
