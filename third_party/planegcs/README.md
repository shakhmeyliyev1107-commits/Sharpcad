# PlaneGCS

2D geometric constraint solver from [FreeCAD](https://github.com/FreeCAD/FreeCAD),
used by the `:sketch` module.

- **Origin**: `src/Mod/Sketcher/App/planegcs/` at tag `1.0.2`
  (commit `256fc7eff3379911ab5daf88e10182c509aa8052`).
- **Licence**: LGPL-2.0-or-later, see [LICENSE](LICENSE).
- **State**: the files under `src/Mod/Sketcher/App/planegcs/` are **unmodified** copies.

To make them compile outside FreeCAD, this directory adds a few files of our own. They
are not part of FreeCAD and each says so in its header:

| File | Stands in for |
|---|---|
| `src/Mod/Sketcher/SketcherGlobal.h` | FreeCAD's export macro header |
| `shim/FCConfig.h` | FreeCAD's platform configuration |
| `shim/Base/Console.h` | FreeCAD's console (messages are dropped) |
| `shim/boost_graph_adjacency_list.hpp` | FreeCAD's warning-suppressing Boost wrapper |
| `CMakeLists.txt` | builds `libplanegcs.so` |

## Rules

- Always built as its own **shared** library; never linked statically into our code.
- Do not edit the FreeCAD files. To update, replace the directory with the files of a
  newer FreeCAD tag and record the tag and commit here.
- Needs Eigen and Boost headers: run `tools/fetch-sketch-deps.sh`.
