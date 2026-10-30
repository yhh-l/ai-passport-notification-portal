#pragma once
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"
#define ESP_PARTITION_TYPE_APP 0
#define ESP_PARTITION_TYPE_DATA 1
typedef struct {uint32_t address, size; const char *label;} esp_partition_t;
const esp_partition_t *esp_partition_find_first(int type, int subtype, const char *name);
esp_err_t esp_partition_erase_range(const esp_partition_t *, size_t, size_t);
esp_err_t esp_partition_write(const esp_partition_t *, size_t, const void *, size_t);
