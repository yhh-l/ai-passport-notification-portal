#include "play_store.h"
#include <string.h>
uint32_t play_reserved(uint32_t size)
{
    if (!size || size > PLAY_POOL_B_SIZE) return 0;
    return (size + PLAY_ALIGNMENT - 1) & ~(PLAY_ALIGNMENT - 1);
}
bool play_range_valid(uint32_t address, uint32_t size)
{
    uint32_t reserved = play_reserved(size);
    if (!reserved || address % PLAY_ALIGNMENT) return false;
    return (address >= PLAY_POOL_A && address <= PLAY_POOL_A + PLAY_POOL_A_SIZE - reserved && reserved <= PLAY_POOL_A_SIZE) ||
           (address >= PLAY_POOL_B && address <= PLAY_POOL_B + PLAY_POOL_B_SIZE - reserved);
}
bool play_catalog_valid(const play_catalog_t *catalog)
{
    if (catalog->magic != PLAY_MAGIC ||
        (catalog->selected != UINT32_MAX && catalog->selected >= PLAY_MAX_COUNT)) return false;
    for (unsigned i = 0; i < PLAY_MAX_COUNT; ++i) {
        const play_entry_t *a = &catalog->entries[i];
        if (!a->size) continue;
        if (a->size < 176 || !play_range_valid(a->address, a->size) ||
            !memchr(a->name, 0, sizeof(a->name))) return false;
        for (unsigned j = 0; j < i; ++j) {
            const play_entry_t *b = &catalog->entries[j];
            if (b->size && a->address < b->address + play_reserved(b->size) &&
                b->address < a->address + play_reserved(a->size)) return false;
        }
    }
    return true;
}
static bool occupied(const play_catalog_t *catalog, uint32_t address)
{
    for (unsigned i = 0; i < PLAY_MAX_COUNT; ++i) {
        const play_entry_t *e = &catalog->entries[i];
        if (e->size && address >= e->address && address < e->address + play_reserved(e->size)) return true;
    }
    return false;
}
static uint32_t scan(const play_catalog_t *catalog, uint32_t requested, bool largest)
{
    uint32_t best = 0, best_size = UINT32_MAX, maximum = 0;
    const uint32_t starts[] = {PLAY_POOL_A, PLAY_POOL_B};
    const uint32_t lengths[] = {PLAY_POOL_A_SIZE, PLAY_POOL_B_SIZE};
    for (unsigned p = 0; p < 2; ++p) {
        uint32_t run = 0, start = starts[p];
        for (uint32_t a = starts[p]; a <= starts[p] + lengths[p]; a += PLAY_ALIGNMENT) {
            if (a < starts[p] + lengths[p] && !occupied(catalog, a)) {
                if (!run) start = a;
                run += PLAY_ALIGNMENT;
            } else {
                if (run > maximum) maximum = run;
                if (requested && run >= requested && run < best_size) { best = start; best_size = run; }
                run = 0;
            }
        }
    }
    return largest ? maximum : best;
}
uint32_t play_allocate(const play_catalog_t *catalog, uint32_t size)
{
    uint32_t reserved = play_reserved(size);
    return reserved ? scan(catalog, reserved, false) : 0;
}
uint32_t play_free(const play_catalog_t *catalog)
{
    uint32_t used = 0;
    for (unsigned i = 0; i < PLAY_MAX_COUNT; ++i) used += play_reserved(catalog->entries[i].size);
    return PLAY_POOL_A_SIZE + PLAY_POOL_B_SIZE - used;
}
uint32_t play_largest(const play_catalog_t *catalog) { return scan(catalog, 0, true); }
uint32_t play_boot_checksum(const play_boot_request_t *r)
{
    /* CRC-32 over the fixed request fields; independent of ESP-IDF and libc. */
    uint32_t crc = UINT32_MAX;
    const uint8_t *data = (const uint8_t *)r;
    for (unsigned i = 0; i < offsetof(play_boot_request_t, checksum); ++i) {
        crc ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1)));
    }
    return ~crc;
}
