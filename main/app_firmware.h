#pragma once

#include "esp_err.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define APP_FIRMWARE_MAX_SLOTS 3
#define APP_FIRMWARE_NAME_MAX 47
#define APP_FIRMWARE_AUTO_SLOT UINT8_MAX

typedef enum {
    APP_FIRMWARE_IDLE = 0,
    APP_FIRMWARE_RECEIVING = 1,
    APP_FIRMWARE_READY = 2,
    APP_FIRMWARE_ERROR = 3,
} app_firmware_state_t;

typedef struct {
    bool available;
    bool present;
    size_t capacity;
    size_t image_size;
    char name[APP_FIRMWARE_NAME_MAX + 1];
} app_firmware_slot_info_t;

void app_firmware_init(void);
esp_err_t app_firmware_begin(uint8_t slot, size_t image_size, const char *display_name);
esp_err_t app_firmware_write(const uint8_t *data, size_t size);
esp_err_t app_firmware_finish(void);
void app_firmware_cancel(esp_err_t reason);
esp_err_t app_firmware_clear(uint8_t slot);
esp_err_t app_firmware_select(uint8_t slot);
esp_err_t app_firmware_prepare_boot(uint8_t slot);

app_firmware_state_t app_firmware_state(void);
bool app_firmware_busy(void);
uint8_t app_firmware_progress(void);
uint8_t app_firmware_active_slot(void);
uint8_t app_firmware_selected_slot(void);
size_t app_firmware_slot_count(void);
bool app_firmware_slot_info(uint8_t slot, app_firmware_slot_info_t *info);
size_t app_firmware_total_free(void);
size_t app_firmware_largest_free_slot(void);
esp_err_t app_firmware_last_error(void);
