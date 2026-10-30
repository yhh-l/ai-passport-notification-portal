#pragma once
#include "esp_err.h"
#include <stdint.h>
#define ESP_IMAGE_VERIFY 0
typedef struct {uint32_t offset, size;} esp_partition_pos_t;
typedef struct {int unused;} esp_image_metadata_t;
esp_err_t esp_image_verify(int, const esp_partition_pos_t *, esp_image_metadata_t *);
