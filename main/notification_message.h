#pragma once

#include <stdint.h>

typedef struct {
    uint8_t type;
    char text[161];
} app_message_t;
