// SharpCAD shim, NOT part of FreeCAD. PlaneGCS reports diagnostics through FreeCAD's
// console; standalone, those messages are dropped.
#pragma once

namespace Base {

class ConsoleSingleton {
public:
    template <typename... Args> void Log(const char*, Args&&...) {}
    template <typename... Args> void Message(const char*, Args&&...) {}
    template <typename... Args> void Warning(const char*, Args&&...) {}
    template <typename... Args> void Error(const char*, Args&&...) {}
};

inline ConsoleSingleton& Console() {
    static ConsoleSingleton instance;
    return instance;
}

}  // namespace Base
