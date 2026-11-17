#include "cad/kernel/shape.h"

#include <cmath>
#include <utility>

#include <BRepBndLib.hxx>
#include <BRepLib_ToolTriangulatedShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Poly_PolygonOnTriangulation.hxx>
#include <Poly_Triangulation.hxx>
#include <STEPControl_Reader.hxx>
#include <STEPControl_Writer.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopLoc_Location.hxx>
#include <TopTools_MapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Trsf.hxx>

#include "cad/kernel/error.h"
#include "shape_impl.h"

namespace cad::kernel {

namespace {

// Smallest dimension we accept; below this OCCT produces degenerate topology.
constexpr double kMinDimension = 1e-6;

bool isValidDimension(double v) { return std::isfinite(v) && v >= kMinDimension; }

void appendPoint(std::vector<float>& out, const gp_Pnt& p) {
    out.push_back(static_cast<float>(p.X()));
    out.push_back(static_cast<float>(p.Y()));
    out.push_back(static_cast<float>(p.Z()));
}

// Appends one face's triangulation to the mesh, in world coordinates.
void appendFace(const TopoDS_Face& face, int faceIndex, MeshData& mesh) {
    TopLoc_Location location;
    Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, location);
    if (tri.IsNull() || tri->NbTriangles() == 0) return;  // face could not be meshed

    if (!tri->HasNormals()) BRepLib_ToolTriangulatedShape::ComputeNormals(face, tri);
    const gp_Trsf trsf = location.Transformation();
    const bool reversed = face.Orientation() == TopAbs_REVERSED;
    const auto base = static_cast<std::uint32_t>(mesh.positions.size() / 3);

    for (int i = 1; i <= tri->NbNodes(); ++i) {
        appendPoint(mesh.positions, tri->Node(i).Transformed(trsf));
        gp_Vec n(0.0, 0.0, 1.0);
        if (tri->HasNormals()) {
            const gp_Dir d = tri->Normal(i);
            n = gp_Vec(d.X(), d.Y(), d.Z()).Transformed(trsf);
            if (reversed) n.Reverse();
        }
        mesh.normals.push_back(static_cast<float>(n.X()));
        mesh.normals.push_back(static_cast<float>(n.Y()));
        mesh.normals.push_back(static_cast<float>(n.Z()));
    }
    for (int i = 1; i <= tri->NbTriangles(); ++i) {
        int a = 0, b = 0, c = 0;
        tri->Triangle(i).Get(a, b, c);
        if (reversed) std::swap(b, c);
        mesh.indices.push_back(base + static_cast<std::uint32_t>(a - 1));
        mesh.indices.push_back(base + static_cast<std::uint32_t>(b - 1));
        mesh.indices.push_back(base + static_cast<std::uint32_t>(c - 1));
        mesh.triangleFaces.push_back(faceIndex);
    }
}

// Feature edges: the polyline each edge has on this face's triangulation.
// Edges shared by two faces are emitted once, tracked in `seen`.
void appendEdges(const TopoDS_Face& face, TopTools_MapOfShape& seen, MeshData& mesh) {
    TopLoc_Location location;
    Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, location);
    if (tri.IsNull()) return;
    const gp_Trsf trsf = location.Transformation();

    for (TopExp_Explorer it(face, TopAbs_EDGE); it.More(); it.Next()) {
        const TopoDS_Edge& edge = TopoDS::Edge(it.Current());
        if (!seen.Add(edge)) continue;  // already drawn from a neighbouring face
        Handle(Poly_PolygonOnTriangulation) poly =
            BRep_Tool::PolygonOnTriangulation(edge, tri, location);
        if (poly.IsNull() || poly->NbNodes() < 2) continue;
        for (int i = 1; i < poly->NbNodes(); ++i) {
            appendPoint(mesh.edgeSegments, tri->Node(poly->Node(i)).Transformed(trsf));
            appendPoint(mesh.edgeSegments, tri->Node(poly->Node(i + 1)).Transformed(trsf));
        }
    }
}

}  // namespace

Shape::Shape(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
Shape::~Shape() = default;

std::string occtVersion() { return OCC_VERSION_COMPLETE; }

std::unique_ptr<Shape> Shape::makeBox(double dx, double dy, double dz) {
    if (!isValidDimension(dx) || !isValidDimension(dy) || !isValidDimension(dz)) {
        throw KernelError(ErrorCode::InvalidArgument,
                          "Box dimensions must be finite and at least 1e-6 mm");
    }
    return guarded("makeBox", [&] {
        auto impl = std::make_unique<Impl>();
        impl->shape = BRepPrimAPI_MakeBox(dx, dy, dz).Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::importStep(const std::string& path) {
    return guarded("importStep", [&] {
        STEPControl_Reader reader;
        if (reader.ReadFile(path.c_str()) != IFSelect_RetDone) {
            throw KernelError(ErrorCode::IoError, "Cannot read STEP file: " + path);
        }
        reader.TransferRoots();
        auto impl = std::make_unique<Impl>();
        impl->shape = reader.OneShape();
        if (impl->shape.IsNull()) {
            throw KernelError(ErrorCode::IoError, "STEP file contains no geometry: " + path);
        }
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

void Shape::exportStep(const std::string& path) const {
    guarded("exportStep", [&] {
        STEPControl_Writer writer;
        if (writer.Transfer(impl_->shape, STEPControl_AsIs) != IFSelect_RetDone ||
            writer.Write(path.c_str()) != IFSelect_RetDone) {
            throw KernelError(ErrorCode::IoError, "Cannot write STEP file: " + path);
        }
        return 0;
    });
}

BoundingBox Shape::boundingBox() const {
    return guarded("boundingBox", [&] {
        Bnd_Box box;
        // Exact geometry, no triangulation, no tolerance enlargement.
        BRepBndLib::AddOptimal(impl_->shape, box, /*useTriangulation=*/false,
                               /*useShapeTolerance=*/false);
        if (box.IsVoid()) {
            throw KernelError(ErrorCode::OcctFailure, "boundingBox: shape has no geometry");
        }
        BoundingBox out{};
        box.Get(out.min[0], out.min[1], out.min[2], out.max[0], out.max[1], out.max[2]);
        return out;
    });
}

MeshData Shape::tessellate(double linearDeflection, double angularDeflection) const {
    if (!std::isfinite(linearDeflection) || linearDeflection <= 0.0 ||
        !std::isfinite(angularDeflection) || angularDeflection <= 0.0) {
        throw KernelError(ErrorCode::InvalidArgument, "Deflections must be positive and finite");
    }
    return guarded("tessellate", [&] {
        // Stores the triangulation on the shape; meshes faces in parallel.
        BRepMesh_IncrementalMesh mesher(impl_->shape, linearDeflection, /*isRelative=*/false,
                                        angularDeflection, /*isInParallel=*/true);
        MeshData mesh;
        TopTools_MapOfShape seenEdges;
        TopTools_IndexedMapOfShape faces;
        TopExp::MapShapes(impl_->shape, TopAbs_FACE, faces);
        for (int i = 1; i <= faces.Extent(); ++i) {
            const TopoDS_Face& face = TopoDS::Face(faces(i));
            appendFace(face, i - 1, mesh);
            appendEdges(face, seenEdges, mesh);
        }
        return mesh;
    });
}

}  // namespace cad::kernel
