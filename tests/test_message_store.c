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
    app_message_t wechat1 = message(2, "微信\n第一条");
    app_message_t lark1 = message(2, "飞书\n项目更新");
    app_message_t wechat2 = message(2, "微信\n第二条");
    app_message_t sms1 = message(1, "信息\n验证码 1234");
    message_store_push(&store, &wechat1);
    message_store_push(&store, &lark1);
    message_store_push(&store, &wechat2);
    message_store_push(&store, &sms1);
    assert(message_store_source_count(&store) == 3);
    char source[64];
    assert(message_store_source_at(&store, 0, source, sizeof(source)));
    assert(strcmp(source, "信息") == 0);
    assert(message_store_source_at(&store, 1, source, sizeof(source)));
    assert(strcmp(source, "微信") == 0);
    assert(message_store_select_latest_source(&store, "微信"));
    assert(strcmp(message_store_current(&store)->text, "微信\n第二条") == 0);
    assert(message_store_count_source(&store, "微信") == 2);
    assert(message_store_current_number_source(&store, "微信") == 2);
    assert(message_store_previous_in_source(&store, "微信"));
    assert(strcmp(message_store_current(&store)->text, "微信\n第一条") == 0);
    assert(!message_store_previous_in_source(&store, "微信"));
    assert(message_store_next_in_source(&store, "微信"));
    assert(message_store_clear_source(&store, "微信") == 2);
    assert(message_store_count(&store) == 2);
    assert(message_store_count_source(&store, "微信") == 0);
    assert(strcmp(message_store_current(&store)->text, "信息\n验证码 1234") == 0);

    message_store_clear(&store);
    assert(message_store_count(&store) == 0);
    puts("message_store: PASS");
    return 0;
}
