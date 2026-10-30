#pragma once
typedef int SemaphoreHandle_t;
static inline int xSemaphoreCreateRecursiveMutex(void) {return 1;}
static inline void xSemaphoreTakeRecursive(int a, int b) {(void)a; (void)b;}
static inline void xSemaphoreGiveRecursive(int a) {(void)a;}
