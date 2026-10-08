#pragma once

#include "notification_message.h"
#include <stdbool.h>
#include <stddef.h>

#define MESSAGE_STORE_CAPACITY 32

typedef struct {
    app_message_t items[MESSAGE_STORE_CAPACITY];
    size_t count;
    size_t current;
} message_store_t;

void message_store_init(message_store_t *store);
// Returns true only when adding the item evicted the oldest stored message.
bool message_store_push(message_store_t *store, const app_message_t *message);
const app_message_t *message_store_current(const message_store_t *store);
bool message_store_previous(message_store_t *store);
bool message_store_next(message_store_t *store);
bool message_store_dismiss_current(message_store_t *store);
void message_store_clear(message_store_t *store);
size_t message_store_count(const message_store_t *store);
size_t message_store_current_number(const message_store_t *store);
void message_store_message_source(const app_message_t *message, char *source, size_t source_size);
size_t message_store_source_count(const message_store_t *store);
bool message_store_source_at(const message_store_t *store, size_t source_index,
                             char *source, size_t source_size);
bool message_store_select_latest_source(message_store_t *store, const char *source);
bool message_store_previous_in_source(message_store_t *store, const char *source);
bool message_store_next_in_source(message_store_t *store, const char *source);
size_t message_store_count_source(const message_store_t *store, const char *source);
size_t message_store_current_number_source(const message_store_t *store, const char *source);
size_t message_store_clear_source(message_store_t *store, const char *source);
