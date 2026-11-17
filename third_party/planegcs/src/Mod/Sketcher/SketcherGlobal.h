// SharpCAD shim, NOT part of FreeCAD. Stands in for FreeCAD's SketcherGlobal.h so the
// PlaneGCS sources under App/planegcs/ compile unmodified as a standalone shared library.
#pragma once

#if defined(_WIN32)
#define SketcherExport __declspec(dllexport)
#else
#define SketcherExport __attribute__((visibility("default")))
#endif
