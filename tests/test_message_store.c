#include "message_store.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static app_message_t message(uint8_t type, const char *text)
{
    app_message_t result = { .type = type };
    snprintf(result.text, sizeof(result.text), "%s", text);
    return result;
}

int main(void)
{
    message_store_t store;
    message_store_init(&store);
    assert(message_store_current(&store) == NULL);

    app_message_t first = message(2, "first");
    app_message_t second = message(1, "second");
    assert(!message_store_push(&store, &first));
    assert(!message_store_push(&store, &second));
    assert(message_store_count(&store) == 2);
    assert(message_store_current_number(&store) == 2);
    assert(strcmp(message_store_current(&store)->text, "second") == 0);

    assert(message_store_previous(&store));
    assert(strcmp(message_store_current(&store)->text, "first") == 0);
    assert(!message_store_previous(&store));
    assert(message_store_next(&store));
    assert(strcmp(message_store_current(&store)->text, "second") == 0);
    assert(!message_store_next(&store));

    assert(message_store_dismiss_current(&store));
    assert(message_store_count(&store) == 1);
    assert(strcmp(message_store_current(&store)->text, "first") == 0);

    message_store_clear(&store);
    for (int i = 0; i < MESSAGE_STORE_CAPACITY + 2; i++) {
        char text[16];
        snprintf(text, sizeof(text), "item-%d", i);
        app_message_t item = message(2, text);
        bool dropped_oldest = message_store_push(&store, &item);
        assert(dropped_oldest == (i >= MESSAGE_STORE_CAPACITY));
    }
    assert(message_store_count(&store) == MESSAGE_STORE_CAPACITY);
    assert(strcmp(store.items[0].text, "item-2") == 0);
    char expected_latest[16];
    snprintf(expected_latest, sizeof(expected_latest), "item-%d", MESSAGE_STORE_CAPACITY + 1);
    assert(strcmp(message_store_current(&store)->text, expected_latest) == 0);

    message_store_clear(&store);
    assert(message_store_count(&store) == 0);
    puts("message_store: PASS");
    return 0;
}
