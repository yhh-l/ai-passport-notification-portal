#include "play_store.h"
#include "bootloader_flash_priv.h"
#include "bootloader_utility.h"
#include "esp_image_format.h"
#include "esp_log.h"
void bootloader_hooks_include(void) {}
void bootloader_after_init(void)
{
    play_boot_request_t request;
    if (bootloader_flash_read(PLAY_BOOT_ADDRESS, &request, sizeof(request), false) != ESP_OK ||
        request.magic != PLAY_BOOT_MAGIC) return;
    /* Consume before executing. Failure/power loss defaults to the installer. */
    if (bootloader_flash_erase_sector(PLAY_BOOT_ADDRESS / 4096) != ESP_OK) return;
    if (request.checksum != play_boot_checksum(&request) ||
        request.size < 176 || !play_range_valid(request.address, request.size)) return;
    bootloader_state_t state = {0};
    state.factory.offset = request.address;
    state.factory.size = request.size;
    esp_image_metadata_t metadata;
    if (esp_image_verify(ESP_IMAGE_VERIFY, &state.factory, &metadata) != ESP_OK) return;
    ESP_LOGI("play_boot", "Starting one-shot play at 0x%lx", (unsigned long)request.address);
    bootloader_utility_load_boot_image(&state, FACTORY_INDEX);
}
