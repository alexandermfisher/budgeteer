/**
 * Monzo domain entities and repositories.
 *
 * <p>This package contains entities related to Monzo bank account connections,
 * including encrypted OAuth token storage.
 *
 * <h2>Entities</h2>
 * <ul>
 *   <li>{@link dev.amf.budgeteer.domain.monzo.MonzoConnection} - Stores encrypted Monzo OAuth tokens</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>Monzo tokens are stored encrypted using AES-256-GCM. The encryption/decryption
 * is handled by {@link dev.amf.budgeteer.service.EncryptionService}, not by the entity itself.
 */
@NullMarked
package dev.amf.budgeteer.domain.monzo;

import org.jspecify.annotations.NullMarked;
