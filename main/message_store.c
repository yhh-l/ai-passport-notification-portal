#include "message_store.h"
#include <string.h>

void message_store_init(message_store_t *store)
{
    if (store) memset(store, 0, sizeof(*store));
}

void message_store_push(message_store_t *store, const app_message_t *message)
{
    if (!store || !message) return;
    if (store->count == MESSAGE_STORE_CAPACITY) {
        memmove(&store->items[0], &store->items[1],
                (MESSAGE_STORE_CAPACITY - 1) * sizeof(store->items[0]));
        store->count--;
    }
    store->items[store->count++] = *message;
    store->current = store->count - 1;
}

const app_message_t *message_store_current(const message_store_t *store)
{
    if (!store || store->count == 0 || store->current >= store->count) return NULL;
    return &store->items[store->current];
}

bool message_store_previous(message_store_t *store)
{
    if (!store || store->count == 0 || store->current == 0) return false;
    store->current--;
    return true;
}

bool message_store_next(message_store_t *store)
{
    if (!store || store->count == 0 || store->current + 1 >= store->count) return false;
    store->current++;
    return true;
}

bool message_store_dismiss_current(message_store_t *store)
{
    if (!store || store->count == 0 || store->current >= store->count) return false;
    if (store->current + 1 < store->count) {
        memmove(&store->items[store->current], &store->items[store->current + 1],
                (store->count - store->current - 1) * sizeof(store->items[0]));
    }
    memset(&store->items[store->count - 1], 0, sizeof(store->items[0]));
    store->count--;
    if (store->count == 0) store->current = 0;
    else if (store->current >= store->count) store->current = store->count - 1;
    return true;
}

void message_store_clear(message_store_t *store)
{
    message_store_init(store);
}

size_t message_store_count(const message_store_t *store)
{
    return store ? store->count : 0;
}

size_t message_store_current_number(const message_store_t *store)
{
    return store && store->count ? store->current + 1 : 0;
}
