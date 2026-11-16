// Host-only tool: writes the sample part bundled with the app (a filleted bracket with
// holes and a boss). Uses OCCT directly because the kernel API has no booleans yet.
//
//   make_sample <output.step>

#include <cstdio>

#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepBndLib.hxx>
#include <Bnd_Box.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <STEPControl_Writer.hxx>
#include <Standard_Failure.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Ax2.hxx>

int main(int argc, char** argv) {
    if (argc != 2) {
        std::fprintf(stderr, "usage: %s <output.step>\n", argv[0]);
        return 2;
    }
    try {
        // Base plate 80 x 50 x 12 mm with a central boss.
        TopoDS_Shape part = BRepPrimAPI_MakeBox(80.0, 50.0, 12.0).Shape();
        const gp_Ax2 bossAxis(gp_Pnt(40.0, 25.0, 12.0), gp::DZ());
        part = BRepAlgoAPI_Fuse(part, BRepPrimAPI_MakeCylinder(bossAxis, 14.0, 18.0).Shape());

        // Through bore in the boss and four mounting holes.
        const gp_Ax2 boreAxis(gp_Pnt(40.0, 25.0, -1.0), gp::DZ());
        part = BRepAlgoAPI_Cut(part, BRepPrimAPI_MakeCylinder(boreAxis, 8.0, 40.0).Shape());
        const double holes[4][2] = {{10.0, 10.0}, {70.0, 10.0}, {10.0, 40.0}, {70.0, 40.0}};
        for (const auto& h : holes) {
            const gp_Ax2 axis(gp_Pnt(h[0], h[1], -1.0), gp::DZ());
            part = BRepAlgoAPI_Cut(part, BRepPrimAPI_MakeCylinder(axis, 3.5, 14.0).Shape());
        }

        // Round the four vertical corners of the plate.
        BRepFilletAPI_MakeFillet fillet(part);
        for (TopExp_Explorer it(part, TopAbs_EDGE); it.More(); it.Next()) {
            const TopoDS_Edge& edge = TopoDS::Edge(it.Current());
            Bnd_Box box;
            BRepBndLib::Add(edge, box);
            double x0, y0, z0, x1, y1, z1;
            box.Get(x0, y0, z0, x1, y1, z1);
            const bool vertical = (x1 - x0) < 0.01 && (y1 - y0) < 0.01 && (z1 - z0) > 11.0;
            const bool atCorner = (x0 < 0.5 || x0 > 79.5) && (y0 < 0.5 || y0 > 49.5);
            if (vertical && atCorner) fillet.Add(6.0, edge);
        }
        part = fillet.Shape();

        STEPControl_Writer writer;
        if (writer.Transfer(part, STEPControl_AsIs) != IFSelect_RetDone ||
            writer.Write(argv[1]) != IFSelect_RetDone) {
            std::fprintf(stderr, "failed to write %s\n", argv[1]);
            return 1;
        }
    } catch (const Standard_Failure& e) {
        std::fprintf(stderr, "OCCT failure: %s\n", e.GetMessageString());
        return 1;
    }
    return 0;
}
