#include "message_store.h"
#include <stdio.h>
#include <string.h>

static const char *fallback_source(uint8_t type)
{
    if (type == 1) return "短信通知";
    if (type == 2) return "应用通知";
    return "连接提示";
}

void message_store_message_source(const app_message_t *message, char *source, size_t source_size)
{
    if (!source || source_size == 0) return;
    source[0] = '\0';
    if (!message) return;

    const char *newline = strchr(message->text, '\n');
    if (newline) {
        size_t length = (size_t)(newline - message->text);
        if (length >= source_size) length = source_size - 1;
        memcpy(source, message->text, length);
        source[length] = '\0';
    }
    if (source[0] == '\0') {
        snprintf(source, source_size, "%s", fallback_source(message->type));
    }
}

static bool source_matches(const app_message_t *message, const char *source)
{
    if (!source || source[0] == '\0') return true;
    char current[64];
    message_store_message_source(message, current, sizeof(current));
    return strcmp(current, source) == 0;
}

void message_store_init(message_store_t *store)
{
    if (store) memset(store, 0, sizeof(*store));
}

bool message_store_push(message_store_t *store, const app_message_t *message)
{
    if (!store || !message) return false;

    bool dropped = store->count == MESSAGE_STORE_CAPACITY;
    if (dropped) {
        memset(&store->items[0], 0, sizeof(store->items[0]));
        memmove(&store->items[0], &store->items[1],
                (MESSAGE_STORE_CAPACITY - 1) * sizeof(store->items[0]));
        store->count--;
    }
    store->items[store->count++] = *message;
    store->current = store->count - 1;
    return dropped;
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

size_t message_store_source_count(const message_store_t *store)
{
    if (!store) return 0;
    size_t unique = 0;
    char source[64];
    char other[64];
    for (size_t reverse = store->count; reverse > 0; reverse--) {
        size_t index = reverse - 1;
        message_store_message_source(&store->items[index], source, sizeof(source));
        bool seen = false;
        for (size_t newer = store->count; newer > index + 1; newer--) {
            message_store_message_source(&store->items[newer - 1], other, sizeof(other));
            if (strcmp(source, other) == 0) {
                seen = true;
                break;
            }
        }
        if (!seen) unique++;
    }
    return unique;
}

bool message_store_source_at(const message_store_t *store, size_t source_index,
                             char *source, size_t source_size)
{
    if (!store || !source || source_size == 0) return false;
    size_t unique = 0;
    char candidate[64];
    char newer_source[64];
    for (size_t reverse = store->count; reverse > 0; reverse--) {
        size_t index = reverse - 1;
        message_store_message_source(&store->items[index], candidate, sizeof(candidate));
        bool seen = false;
        for (size_t newer = store->count; newer > index + 1; newer--) {
            message_store_message_source(&store->items[newer - 1], newer_source,
                                         sizeof(newer_source));
            if (strcmp(candidate, newer_source) == 0) {
                seen = true;
                break;
            }
        }
        if (seen) continue;
        if (unique++ == source_index) {
            snprintf(source, source_size, "%s", candidate);
            return true;
        }
    }
    source[0] = '\0';
    return false;
}

bool message_store_select_latest_source(message_store_t *store, const char *source)
{
    if (!store || store->count == 0) return false;
    for (size_t reverse = store->count; reverse > 0; reverse--) {
        size_t index = reverse - 1;
        if (source_matches(&store->items[index], source)) {
            store->current = index;
            return true;
        }
    }
    return false;
}

bool message_store_previous_in_source(message_store_t *store, const char *source)
{
    if (!store || store->count == 0 || store->current == 0) return false;
    for (size_t index = store->current; index > 0; index--) {
        if (source_matches(&store->items[index - 1], source)) {
            store->current = index - 1;
            return true;
        }
    }
    return false;
}

bool message_store_next_in_source(message_store_t *store, const char *source)
{
    if (!store || store->count == 0 || store->current + 1 >= store->count) return false;
    for (size_t index = store->current + 1; index < store->count; index++) {
        if (source_matches(&store->items[index], source)) {
            store->current = index;
            return true;
        }
    }
    return false;
}

size_t message_store_count_source(const message_store_t *store, const char *source)
{
    if (!store) return 0;
    size_t count = 0;
    for (size_t index = 0; index < store->count; index++) {
        if (source_matches(&store->items[index], source)) count++;
    }
    return count;
}

size_t message_store_current_number_source(const message_store_t *store, const char *source)
{
    if (!store || store->count == 0 || store->current >= store->count ||
        !source_matches(&store->items[store->current], source)) return 0;
    size_t number = 0;
    for (size_t index = 0; index <= store->current; index++) {
        if (source_matches(&store->items[index], source)) number++;
    }
    return number;
}

size_t message_store_clear_source(message_store_t *store, const char *source)
{
    if (!store || !source || source[0] == '\0') return 0;
    size_t write = 0;
    size_t removed = 0;
    for (size_t read = 0; read < store->count; read++) {
        if (source_matches(&store->items[read], source)) {
            removed++;
            continue;
        }
        if (write != read) store->items[write] = store->items[read];
        write++;
    }
    if (removed == 0) return 0;
    memset(&store->items[write], 0,
           (MESSAGE_STORE_CAPACITY - write) * sizeof(store->items[0]));
    store->count = write;
    store->current = write == 0 ? 0 : write - 1;
    return removed;
}
