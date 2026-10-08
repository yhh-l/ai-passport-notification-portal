#pragma once
#include "notification_message.h"
#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

esp_err_t app_ble_start(void);
bool app_ble_receive(app_message_t *message);
bool app_ble_connected(void);
bool app_ble_authenticated(void);
uint32_t app_ble_passkey(void); // 0xffffffff when no pairing is pending
