#pragma once
#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

typedef struct {
    uint8_t type;
    char text[161];
} app_message_t;

esp_err_t app_ble_start(void);
bool app_ble_receive(app_message_t *message);
bool app_ble_connected(void);
bool app_ble_authenticated(void);
uint32_t app_ble_passkey(void); // 0xffffffff when no pairing is pending
