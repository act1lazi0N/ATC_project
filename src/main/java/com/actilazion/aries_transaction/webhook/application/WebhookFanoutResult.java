package com.actilazion.aries_transaction.webhook.application;

public record WebhookFanoutResult(int eligibleEndpointCount, int createdDeliveryCount) {
}
