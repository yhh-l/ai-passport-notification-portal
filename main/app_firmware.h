#pragma once

#include "esp_err.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef enum {
    APP_FIRMWARE_IDLE = 0,
    APP_FIRMWARE_RECEIVING = 1,
    APP_FIRMWARE_READY = 2,
    APP_FIRMWARE_ERROR = 3,
} app_firmware_state_t;

void app_firmware_init(void);
esp_err_t app_firmware_begin(size_t image_size, const char *display_name);
esp_err_t app_firmware_write(const uint8_t *data, size_t size);
esp_err_t app_firmware_finish(void);
void app_firmware_cancel(esp_err_t reason);
esp_err_t app_firmware_clear(void);
esp_err_t app_firmware_prepare_boot(void);

app_firmware_state_t app_firmware_state(void);
bool app_firmware_busy(void);
bool app_firmware_slot_present(void);
uint8_t app_firmware_progress(void);
size_t app_firmware_image_size(void);
const char *app_firmware_slot_name(void);
esp_err_t app_firmware_last_error(void);
