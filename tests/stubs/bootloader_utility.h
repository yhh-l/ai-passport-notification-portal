#pragma once
#include "esp_image_format.h"
#define FACTORY_INDEX -1
typedef struct {esp_partition_pos_t factory;} bootloader_state_t;
void bootloader_utility_load_boot_image(const bootloader_state_t *, int);
