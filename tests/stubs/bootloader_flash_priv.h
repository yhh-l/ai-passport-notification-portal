#pragma once
#include "esp_err.h"
#include <stddef.h>
#include <stdbool.h>
esp_err_t bootloader_flash_read(size_t, void *, size_t, bool);
esp_err_t bootloader_flash_erase_sector(size_t);
