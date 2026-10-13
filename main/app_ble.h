#pragma once

#include "esp_err.h"
#include <stdbool.h>
#include <stdint.h>

esp_err_t app_ble_start(void);
bool app_ble_connected(void);
bool app_ble_authenticated(void);
uint32_t app_ble_passkey(void); // UINT32_MAX when no pairing is pending
