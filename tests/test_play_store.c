#include "play_store.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static play_catalog_t catalog;
int main(void)
{
    catalog.magic = PLAY_MAGIC;
    catalog.selected = UINT32_MAX;
    assert(play_catalog_valid(&catalog));
    assert(play_free(&catalog) == 0x6c0000);
    assert(play_allocate(&catalog, 0) == 0);
    assert(play_allocate(&catalog, UINT32_MAX) == 0);
    assert(!play_range_valid(0x410000, 176));
    assert(!play_range_valid(0x416000, 176));
    assert(!play_range_valid(0x800000, 176));
    assert(!play_range_valid(0x13ffff, 176));
    assert(!play_range_valid(PLAY_POOL_A, PLAY_POOL_B_SIZE));
    /* More than three plays, best-fit, alignment and fragmented deletion. */
    for (unsigned i = 0; i < PLAY_MAX_COUNT; ++i) {
        uint32_t a = play_allocate(&catalog, 176);
        assert(a && play_range_valid(a, 176));
        catalog.entries[i] = (play_entry_t){.address = a, .size = 176};
        assert(play_catalog_valid(&catalog));
    }
    assert(play_free(&catalog) == 0 && play_largest(&catalog) == 0);
    assert(!play_allocate(&catalog, 176));
    uint32_t hole = catalog.entries[5].address;
    catalog.entries[5].size = 0;
    assert(play_allocate(&catalog, 176) == hole);
    assert(play_largest(&catalog) == PLAY_ALIGNMENT);
    assert(!play_allocate(&catalog, PLAY_ALIGNMENT + 1));
    catalog.entries[6].size = 0;
    assert(play_allocate(&catalog, PLAY_ALIGNMENT + 1) == hole);
    catalog.entries[5] = catalog.entries[4];
    assert(!play_catalog_valid(&catalog));
    memset(&catalog, 0, sizeof(catalog));
    catalog.magic = PLAY_MAGIC;
    catalog.selected = UINT32_MAX;
    /* Boundary allocations cannot cross protected identity / boot sectors. */
    catalog.entries[0] = (play_entry_t){.address = PLAY_POOL_A, .size = PLAY_POOL_A_SIZE};
    assert(play_allocate(&catalog, PLAY_POOL_B_SIZE) == PLAY_POOL_B);
    catalog.entries[1] = (play_entry_t){.address = PLAY_POOL_B, .size = PLAY_POOL_B_SIZE};
    assert(play_catalog_valid(&catalog) && play_free(&catalog) == 0);
    play_boot_request_t request = {.magic = PLAY_BOOT_MAGIC, .address = PLAY_POOL_B, .size = 1048576};
    request.checksum = play_boot_checksum(&request);
    assert(request.checksum == play_boot_checksum(&request));
    request.address += PLAY_ALIGNMENT;
    assert(request.checksum != play_boot_checksum(&request));
    puts("play_store: allocation, 108-entry capacity, fragmentation, boundaries, catalog validation, boot CRC passed");
}
