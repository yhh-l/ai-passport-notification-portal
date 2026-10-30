#include "app_firmware.h"
#include "play_store.h"
#include "esp_app_desc.h"
#include "esp_image_format.h"
#include "esp_partition.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include <stdio.h>
#include <string.h>

static play_catalog_t s_catalog, s_staged;
static play_entry_t s_pending;
static const esp_partition_t *s_pools[2], *s_boot;
static app_firmware_state_t s_state;
static esp_err_t s_error;
static uint32_t s_written;
static uint8_t s_active = APP_FIRMWARE_AUTO_SLOT;
static bool s_healthy;
static uint32_t s_total_free, s_largest_free;
static SemaphoreHandle_t s_mutex;

static const esp_partition_t *pool_for(uint32_t address)
{
    for (unsigned i = 0; i < 2; ++i) {
        if (s_pools[i] && address >= s_pools[i]->address &&
            address < s_pools[i]->address + s_pools[i]->size) return s_pools[i];
    }
    return NULL;
}
static esp_err_t verify_entry(const play_entry_t *entry)
{
    if (!play_range_valid(entry->address, entry->size)) return ESP_ERR_INVALID_SIZE;
    esp_partition_pos_t position = {.offset = entry->address, .size = entry->size};
    esp_image_metadata_t metadata;
    return esp_image_verify(ESP_IMAGE_VERIFY, &position, &metadata);
}
static esp_err_t fail(esp_err_t error)
{
    s_error = error;
    s_state = APP_FIRMWARE_ERROR;
    s_active = APP_FIRMWARE_AUTO_SLOT;
    memset(&s_pending, 0, sizeof(s_pending));
    return error;
}
/* NVS commits a complete catalog blob. Never reuse uncertain metadata in RAM. */
static esp_err_t persist(void)
{
    if (!play_catalog_valid(&s_staged)) return ESP_ERR_INVALID_STATE;
    nvs_handle_t handle;
    esp_err_t error = nvs_open_from_partition("play_index", "play_store_v3", NVS_READWRITE, &handle);
    if (error == ESP_OK) {
        error = nvs_set_blob(handle, "catalog", &s_staged, sizeof(s_staged));
        if (error == ESP_OK) error = nvs_commit(handle);
        nvs_close(handle);
    }
    if (error == ESP_OK) {
        s_catalog = s_staged;
        s_total_free = play_free(&s_catalog);
        s_largest_free = play_largest(&s_catalog);
    }
    else s_healthy = false; /* reboot and reload before any further flash mutation */
    return error;
}
static esp_err_t load_or_migrate(void)
{
    nvs_handle_t handle;
    esp_err_t error = nvs_open_from_partition("play_index", "play_store_v3", NVS_READONLY, &handle);
    if (error == ESP_OK) {
        size_t length = sizeof(s_catalog);
        error = nvs_get_blob(handle, "catalog", &s_catalog, &length);
        nvs_close(handle);
        if (error == ESP_OK) return length == sizeof(s_catalog) && play_catalog_valid(&s_catalog) ? ESP_OK : ESP_ERR_INVALID_STATE;
        if (error != ESP_ERR_NVS_NOT_FOUND) return error;
    } else if (error != ESP_ERR_NVS_NOT_FOUND) return error;

    memset(&s_staged, 0, sizeof(s_staged));
    s_staged.magic = PLAY_MAGIC;
    s_staged.selected = UINT32_MAX;
    error = nvs_open("fw_slots", NVS_READONLY, &handle);
    if (error == ESP_OK) {
        const uint32_t addresses[] = {0x130000, 0x2a0000, 0x420000};
        const uint32_t capacities[] = {0x170000, 0x170000, 0x3e0000};
        for (unsigned i = 0; i < 3; ++i) {
            char key[16];
            uint8_t valid = 0;
            snprintf(key, sizeof(key), "s%u_valid", i);
            error = nvs_get_u8(handle, key, &valid);
            if (error == ESP_ERR_NVS_NOT_FOUND || (error == ESP_OK && !valid)) continue;
            if (error != ESP_OK) break;
            play_entry_t *entry = &s_staged.entries[i];
            snprintf(key, sizeof(key), "s%u_size", i);
            error = nvs_get_u32(handle, key, &entry->size);
            entry->address = addresses[i];
            if (error != ESP_OK || entry->size > capacities[i] || verify_entry(entry) != ESP_OK) {
                error = ESP_ERR_INVALID_STATE;
                break; /* Never overwrite a damaged legacy library automatically. */
            }
            snprintf(key, sizeof(key), "s%u_name", i);
            size_t length = sizeof(entry->name);
            if (nvs_get_str(handle, key, entry->name, &length) != ESP_OK)
                snprintf(entry->name, sizeof(entry->name), "Imported play %u", i + 1);
            if (s_staged.selected == UINT32_MAX) s_staged.selected = i;
            error = ESP_OK;
        }
        nvs_close(handle);
        if (error != ESP_OK && error != ESP_ERR_NVS_NOT_FOUND) return error;
    } else if (error != ESP_ERR_NVS_NOT_FOUND) return error;
    return persist();
}
void app_firmware_init(void)
{
    s_state = APP_FIRMWARE_IDLE;
    s_error = ESP_OK;
    s_active = APP_FIRMWARE_AUTO_SLOT;
    s_total_free = s_largest_free = 0;
    memset(&s_pending, 0, sizeof(s_pending));
    s_mutex = xSemaphoreCreateRecursiveMutex();
    configASSERT(s_mutex);
    s_pools[0] = esp_partition_find_first(ESP_PARTITION_TYPE_APP, 0x40, "play_a");
    s_pools[1] = esp_partition_find_first(ESP_PARTITION_TYPE_APP, 0x41, "play_b");
    s_boot = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, 0x40, "play_boot");
    s_catalog.magic = PLAY_MAGIC;
    s_catalog.selected = UINT32_MAX;
    s_healthy = s_pools[0] && s_pools[1] && s_boot &&
        s_pools[0]->address == PLAY_POOL_A && s_pools[0]->size == PLAY_POOL_A_SIZE &&
        s_pools[1]->address == PLAY_POOL_B && s_pools[1]->size == PLAY_POOL_B_SIZE &&
        s_boot->address == PLAY_BOOT_ADDRESS && s_boot->size == 4096;
    if (!s_healthy) { fail(ESP_ERR_INVALID_STATE); return; }
    esp_err_t error = nvs_flash_init_partition("play_index");
    if (error == ESP_OK) error = load_or_migrate();
    s_healthy = error == ESP_OK;
    if (s_healthy) { s_total_free = play_free(&s_catalog); s_largest_free = play_largest(&s_catalog); }
    if (!s_healthy) {
        memset(&s_catalog, 0, sizeof(s_catalog));
        s_catalog.magic = PLAY_MAGIC;
        s_catalog.selected = UINT32_MAX;
        fail(error);
    }
}
static esp_err_t firmware_begin(uint8_t slot, size_t image_size, const char *name)
{
    if (!s_healthy || s_state == APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    /* Existing IDs are never overwritten: delete explicitly, then install. */
    if (slot == APP_FIRMWARE_AUTO_SLOT) {
        for (unsigned i = 0; i < PLAY_MAX_COUNT; ++i)
            if (!s_catalog.entries[i].size) { slot = i; break; }
    }
    if (slot >= PLAY_MAX_COUNT || s_catalog.entries[slot].size) return ESP_ERR_INVALID_ARG;
    if (image_size < 176 || image_size > PLAY_POOL_B_SIZE) return ESP_ERR_INVALID_SIZE;
    uint32_t address = play_allocate(&s_catalog, image_size);
    if (!address) return fail(ESP_ERR_NO_MEM);
    s_pending = (play_entry_t){.address = address, .size = image_size};
    snprintf(s_pending.name, sizeof(s_pending.name), "%s", name && *name ? name : "Untitled play");
    const esp_partition_t *pool = pool_for(address);
    esp_err_t error = esp_partition_erase_range(pool, address - pool->address, play_reserved(image_size));
    if (error != ESP_OK) return fail(error);
    s_written = 0;
    s_active = slot;
    s_error = ESP_OK;
    s_state = APP_FIRMWARE_RECEIVING;
    return ESP_OK;
}
static esp_err_t firmware_write(const uint8_t *data, size_t size)
{
    if (s_state != APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    if (!data || !size || size > s_pending.size - s_written) return fail(ESP_ERR_INVALID_SIZE);
    const esp_partition_t *pool = pool_for(s_pending.address);
    esp_err_t error = esp_partition_write(pool, s_pending.address - pool->address + s_written, data, size);
    if (error != ESP_OK) return fail(error);
    s_written += size;
    return ESP_OK;
}
static esp_err_t firmware_finish(void)
{
    if (s_state != APP_FIRMWARE_RECEIVING || s_written != s_pending.size) return fail(ESP_ERR_INVALID_STATE);
    esp_err_t error = verify_entry(&s_pending);
    if (error != ESP_OK) return fail(error);
    s_staged = s_catalog;
    s_staged.entries[s_active] = s_pending;
    s_staged.selected = s_active;
    error = persist();
    if (error != ESP_OK) return fail(error);
    s_active = APP_FIRMWARE_AUTO_SLOT;
    s_state = APP_FIRMWARE_READY;
    return ESP_OK;
}
static void firmware_cancel(esp_err_t reason)
{
    if (s_state == APP_FIRMWARE_RECEIVING) fail(reason == ESP_OK ? ESP_FAIL : reason);
}
static esp_err_t firmware_clear(uint8_t slot)
{
    if (!s_healthy || s_state == APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    if (slot >= PLAY_MAX_COUNT || !s_catalog.entries[slot].size) return ESP_ERR_NOT_FOUND;
    s_staged = s_catalog;
    memset(&s_staged.entries[slot], 0, sizeof(play_entry_t));
    if (s_staged.selected == slot) {
        s_staged.selected = UINT32_MAX;
        for (unsigned i = 0; i < PLAY_MAX_COUNT; ++i)
            if (s_staged.entries[i].size) { s_staged.selected = i; break; }
    }
    esp_err_t error = persist();
    if (error != ESP_OK) return fail(error);
    s_state = APP_FIRMWARE_IDLE;
    return ESP_OK;
}
static esp_err_t firmware_select(uint8_t slot)
{
    if (!s_healthy || s_state == APP_FIRMWARE_RECEIVING) return ESP_ERR_INVALID_STATE;
    if (slot >= PLAY_MAX_COUNT || !s_catalog.entries[slot].size) return ESP_ERR_NOT_FOUND;
    if (s_catalog.selected == slot) return ESP_OK;
    s_staged = s_catalog;
    s_staged.selected = slot;
    esp_err_t error = persist();
    return error == ESP_OK ? ESP_OK : fail(error);
}
static esp_err_t firmware_prepare_boot(uint8_t slot)
{
    if (slot == APP_FIRMWARE_AUTO_SLOT) slot = s_catalog.selected;
    esp_err_t error = firmware_select(slot);
    if (error != ESP_OK) return error;
    const play_entry_t *entry = &s_catalog.entries[slot];
    error = verify_entry(entry);
    if (error != ESP_OK) return fail(error);
    play_boot_request_t request = {.magic = PLAY_BOOT_MAGIC, .address = entry->address, .size = entry->size};
    request.checksum = play_boot_checksum(&request);
    error = esp_partition_erase_range(s_boot, 0, 4096);
    if (error == ESP_OK) error = esp_partition_write(s_boot, 0, &request, sizeof(request));
    /* Block BLE mutations between arming the request and restarting. */
    if (error == ESP_OK) s_healthy = false;
    return error == ESP_OK ? ESP_OK : fail(error);
}
static app_firmware_state_t firmware_state(void) { return s_state; }
static bool firmware_busy(void) { return !s_healthy || s_state == APP_FIRMWARE_RECEIVING; }
static uint8_t firmware_active_slot(void) { return s_active; }
static uint8_t firmware_selected_slot(void) { return s_catalog.selected; }
static size_t firmware_slot_count(void) { return PLAY_MAX_COUNT; }
static uint8_t firmware_progress(void) { return s_state == APP_FIRMWARE_RECEIVING ? s_written * 100 / s_pending.size : 0; }
static bool firmware_slot_info(uint8_t slot, app_firmware_slot_info_t *info)
{
    if (!info || slot >= PLAY_MAX_COUNT) return false;
    memset(info, 0, sizeof(*info));
    const play_entry_t *entry = &s_catalog.entries[slot];
    info->available = s_healthy;
    info->present = entry->size != 0;
    info->capacity = entry->size ? play_reserved(entry->size) : s_largest_free;
    info->image_size = entry->size;
    snprintf(info->name, sizeof(info->name), "%s", entry->name);
    return true;
}
static size_t firmware_total_free(void) { return s_healthy ? s_total_free : 0; }
static size_t firmware_largest_free_slot(void) { return s_healthy ? s_largest_free : 0; }
static esp_err_t firmware_last_error(void) { return s_error; }

esp_err_t app_firmware_begin(uint8_t slot, size_t size, const char *name)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_begin(slot, size, name);
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

esp_err_t app_firmware_write(const uint8_t *data, size_t size)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_write(data, size);
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

esp_err_t app_firmware_finish(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_finish();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

void app_firmware_cancel(esp_err_t reason)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    firmware_cancel(reason);
    xSemaphoreGiveRecursive(s_mutex);
}

esp_err_t app_firmware_clear(uint8_t slot)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_clear(slot);
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

esp_err_t app_firmware_select(uint8_t slot)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_select(slot);
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

esp_err_t app_firmware_prepare_boot(uint8_t slot)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_prepare_boot(slot);
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

app_firmware_state_t app_firmware_state(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    app_firmware_state_t result = firmware_state();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

bool app_firmware_busy(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    bool result = firmware_busy();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

uint8_t app_firmware_active_slot(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    uint8_t result = firmware_active_slot();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

uint8_t app_firmware_selected_slot(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    uint8_t result = firmware_selected_slot();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

size_t app_firmware_slot_count(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    size_t result = firmware_slot_count();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

uint8_t app_firmware_progress(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    uint8_t result = firmware_progress();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

bool app_firmware_slot_info(uint8_t slot, app_firmware_slot_info_t *info)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    bool result = firmware_slot_info(slot, info);
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

size_t app_firmware_total_free(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    size_t result = firmware_total_free();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

size_t app_firmware_largest_free_slot(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    size_t result = firmware_largest_free_slot();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}

esp_err_t app_firmware_last_error(void)
{
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    esp_err_t result = firmware_last_error();
    xSemaphoreGiveRecursive(s_mutex);
    return result;
}
