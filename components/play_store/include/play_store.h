#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define PLAY_ALIGNMENT 0x10000u
#define PLAY_POOL_A 0x130000u
#define PLAY_POOL_A_SIZE 0x2e0000u
#define PLAY_POOL_B 0x420000u
#define PLAY_POOL_B_SIZE 0x3e0000u
#define PLAY_BOOT_ADDRESS 0x416000u
/* One entry for every physically possible 64 KiB allocation, not a UI quota. */
#define PLAY_MAX_COUNT ((PLAY_POOL_A_SIZE + PLAY_POOL_B_SIZE) / PLAY_ALIGNMENT)
#define PLAY_NAME_MAX 47
#define PLAY_MAGIC 0x504c5933u
#define PLAY_BOOT_MAGIC 0x42545933u

typedef struct {
    uint32_t address;
    uint32_t size;
    char name[PLAY_NAME_MAX + 1];
} play_entry_t;
typedef struct {
    uint32_t magic;
    uint32_t selected;
    play_entry_t entries[PLAY_MAX_COUNT];
} play_catalog_t;
typedef struct {
    uint32_t magic, address, size, checksum;
} play_boot_request_t;
uint32_t play_reserved(uint32_t size);
bool play_range_valid(uint32_t address, uint32_t size);
bool play_catalog_valid(const play_catalog_t *catalog);
uint32_t play_allocate(const play_catalog_t *catalog, uint32_t size);
uint32_t play_free(const play_catalog_t *catalog);
uint32_t play_largest(const play_catalog_t *catalog);
uint32_t play_boot_checksum(const play_boot_request_t *request);
