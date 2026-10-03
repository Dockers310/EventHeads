
package ru.doksi.eventheads.integrations;

// RU: Снимок состояния необязательных интеграций.
// EN: Snapshot of optional integration availability.
public record IntegrationStatus(boolean worldGuard, boolean worldEdit, boolean crazyVouchers, boolean vault) {}
