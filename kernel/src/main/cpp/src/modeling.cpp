// Solid modeling operations: profiles from sketches, extrude, revolve, booleans,
// fillet/chamfer, push/pull, picking.

#include <cmath>
#include <utility>

#include <BOPAlgo_Builder.hxx>
#include <BOPAlgo_Tools.hxx>
#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <GCPnts_QuasiUniformDeflection.hxx>
#include <HLRAlgo_Projector.hxx>
#include <HLRBRep_Algo.hxx>
#include <HLRBRep_HLRToShape.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepClass_FaceClassifier.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepGProp.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepOffsetAPI_ThruSections.hxx>
#include <BRepOffsetAPI_MakePipe.hxx>
#include <TopoDS_Wire.hxx>
#include <BRepTools.hxx>
#include <BRepGProp_Face.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepPrimAPI_MakeRevol.hxx>
#include <BRep_Builder.hxx>
#include <GProp_GProps.hxx>
#include <IntCurvesFace_ShapeIntersector.hxx>
#include <ShapeUpgrade_UnifySameDomain.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <gp_Ax1.hxx>
#include <gp_Ax2.hxx>
#include <gp_Circ.hxx>
#include <gp_Lin.hxx>
#include <gp_Pnt.hxx>
#include <gp_Vec.hxx>

#include "shape_impl.h"

namespace cad::kernel {

namespace {

constexpr double kTiny = 1e-7;

void require(bool condition, const char* message) {
    if (!condition) throw KernelError(ErrorCode::InvalidArgument, message);
}

bool finite3(const double v[3]) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

gp_Pnt planePoint(const SketchProfileInput& in, double u, double v) {
    return gp_Pnt(in.origin[0] + u * in.xAxis[0] + v * in.yAxis[0],
                  in.origin[1] + u * in.xAxis[1] + v * in.yAxis[1],
                  in.origin[2] + u * in.xAxis[2] + v * in.yAxis[2]);
}

gp_Dir planeNormal(const SketchProfileInput& in) {
    const gp_Vec x(in.xAxis[0], in.xAxis[1], in.xAxis[2]);
    const gp_Vec y(in.yAxis[0], in.yAxis[1], in.yAxis[2]);
    const gp_Vec n = x.Crossed(y);
    require(n.Magnitude() > 0.5, "Plane axes must be unit length and perpendicular");
    return gp_Dir(n);
}

TopTools_IndexedMapOfShape facesOf(const TopoDS_Shape& shape) {
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(shape, TopAbs_FACE, faces);
    return faces;
}

const TopoDS_Face& faceAt(const TopTools_IndexedMapOfShape& faces, int index) {
    require(index >= 0 && index < faces.Extent(), "Face index out of range");
    return TopoDS::Face(faces(index + 1));
}

// Merges coplanar faces and collinear edges left behind by booleans.
TopoDS_Shape tidy(const TopoDS_Shape& shape) {
    ShapeUpgrade_UnifySameDomain unify(shape, Standard_True, Standard_True, Standard_True);
    unify.Build();
    return unify.Shape();
}

template <typename Algo>
TopoDS_Shape runBoolean(Algo& algo, const char* what) {
    algo.Build();
    if (!algo.IsDone() || algo.HasErrors()) {
        throw KernelError(ErrorCode::OcctFailure, std::string(what) + " failed");
    }
    return algo.Shape();
}

bool hasFaces(const TopoDS_Shape& shape) {
    return TopExp_Explorer(shape, TopAbs_FACE).More();
}

}  // namespace

namespace {
// Model-space edges of a sketch's lines, circles and arcs.
TopTools_ListOfShape sketchEdges(const SketchProfileInput& in, const gp_Dir& normal) {
    TopTools_ListOfShape edgeList;
    for (size_t i = 0; i < in.lines.size(); i += 4) {
        const gp_Pnt a = planePoint(in, in.lines[i], in.lines[i + 1]);
        const gp_Pnt b = planePoint(in, in.lines[i + 2], in.lines[i + 3]);
        if (a.Distance(b) < kTiny) continue;
        edgeList.Append(BRepBuilderAPI_MakeEdge(a, b).Edge());
    }
    for (size_t i = 0; i < in.circles.size(); i += 3) {
        const double r = in.circles[i + 2];
        require(std::isfinite(r) && r > kTiny, "Circle radius must be positive");
        const gp_Ax2 axes(planePoint(in, in.circles[i], in.circles[i + 1]), normal,
                          gp_Dir(in.xAxis[0], in.xAxis[1], in.xAxis[2]));
        edgeList.Append(BRepBuilderAPI_MakeEdge(gp_Circ(axes, r)).Edge());
    }
    require(in.arcs.size() % 5 == 0, "Malformed arc array");
    for (size_t i = 0; i < in.arcs.size(); i += 5) {
        const double r = in.arcs[i + 2];
        require(std::isfinite(r) && r > kTiny, "Arc radius must be positive");
        const gp_Ax2 axes(planePoint(in, in.arcs[i], in.arcs[i + 1]), normal,
                          gp_Dir(in.xAxis[0], in.xAxis[1], in.xAxis[2]));
        edgeList.Append(BRepBuilderAPI_MakeEdge(gp_Circ(axes, r), in.arcs[i + 3], in.arcs[i + 4]).Edge());
    }
    return edgeList;
}
}  // namespace

std::unique_ptr<Shape> Shape::profileFromSketch(const SketchProfileInput& in) {
    require(finite3(in.origin) && finite3(in.xAxis) && finite3(in.yAxis), "Plane must be finite");
    require(in.lines.size() % 4 == 0 && in.circles.size() % 3 == 0, "Malformed sketch arrays");
    const gp_Dir normal = planeNormal(in);
    return guarded("profileFromSketch", [&] {
        // Splitting edges where they cross lets crossing lines close up a region.
        TopTools_ListOfShape edgeList = sketchEdges(in, normal);
        require(!edgeList.IsEmpty(), "Sketch is empty");

        TopoDS_Shape split;
        if (edgeList.Extent() == 1) {
            split = edgeList.First();  // the general fuse refuses a single argument
        } else {
            BOPAlgo_Builder fuse;
            fuse.SetArguments(edgeList);
            fuse.Perform();
            if (fuse.HasErrors()) throw KernelError(ErrorCode::OcctFailure, "Could not intersect sketch edges");
            split = fuse.Shape();
        }

        TopoDS_Shape wires, faces;
        BOPAlgo_Tools::EdgesToWires(split, wires, Standard_True);
        if (!BOPAlgo_Tools::WiresToFaces(wires, faces) || !hasFaces(faces)) {
            throw KernelError(ErrorCode::InvalidArgument, "Sketch encloses no region");
        }
        auto impl = std::make_unique<Impl>();
        impl->shape = faces;
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

int Shape::regionAt(const SketchProfileInput& in, double u, double v) const {
    require(std::isfinite(u) && std::isfinite(v), "Point must be finite");
    return guarded("regionAt", [&] {
        const gp_Pnt p = planePoint(in, u, v);
        const auto faces = facesOf(impl_->shape);
        for (int i = 1; i <= faces.Extent(); ++i) {
            BRepClass_FaceClassifier classifier(TopoDS::Face(faces(i)), p, 1e-6);
            if (classifier.State() == TopAbs_IN) return i - 1;
        }
        return -1;
    });
}

std::unique_ptr<Shape> Shape::subset(const std::vector<int>& indices) const {
    return guarded("subset", [&] {
        const auto faces = facesOf(impl_->shape);
        TopoDS_Compound compound;
        BRep_Builder builder;
        builder.MakeCompound(compound);
        for (int index : indices) builder.Add(compound, faceAt(faces, index));
        require(!indices.empty(), "Select at least one region");
        auto impl = std::make_unique<Impl>();
        impl->shape = compound;
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::extrude(double dx, double dy, double dz) const {
    require(std::isfinite(dx) && std::isfinite(dy) && std::isfinite(dz), "Vector must be finite");
    require(std::sqrt(dx * dx + dy * dy + dz * dz) > kTiny, "Extrude distance must not be zero");
    return guarded("extrude", [&] {
        BRepPrimAPI_MakePrism prism(impl_->shape, gp_Vec(dx, dy, dz));
        prism.Build();
        if (!prism.IsDone()) throw KernelError(ErrorCode::OcctFailure, "extrude failed");
        auto impl = std::make_unique<Impl>();
        impl->shape = prism.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::revolve(const double axisOrigin[3], const double axisDirection[3],
                                      double angle) const {
    require(finite3(axisOrigin) && finite3(axisDirection), "Axis must be finite");
    require(std::isfinite(angle) && angle > kTiny && angle <= 2.0 * M_PI + kTiny,
            "Angle must be in (0, 360] degrees");
    const gp_Vec dir(axisDirection[0], axisDirection[1], axisDirection[2]);
    require(dir.Magnitude() > kTiny, "Axis direction must not be zero");
    return guarded("revolve", [&] {
        const gp_Ax1 axis(gp_Pnt(axisOrigin[0], axisOrigin[1], axisOrigin[2]), gp_Dir(dir));
        BRepPrimAPI_MakeRevol revol(impl_->shape, axis, std::min(angle, 2.0 * M_PI));
        revol.Build();
        if (!revol.IsDone()) throw KernelError(ErrorCode::OcctFailure, "revolve failed");
        auto impl = std::make_unique<Impl>();
        impl->shape = revol.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::booleanWith(const Shape& tool, BooleanOp op) const {
    return guarded("boolean", [&] {
        TopoDS_Shape result;
        switch (op) {
            case BooleanOp::Union: {
                BRepAlgoAPI_Fuse algo(impl_->shape, tool.impl_->shape);
                result = runBoolean(algo, "union");
                break;
            }
            case BooleanOp::Subtract: {
                BRepAlgoAPI_Cut algo(impl_->shape, tool.impl_->shape);
                result = runBoolean(algo, "subtract");
                break;
            }
            case BooleanOp::Intersect: {
                BRepAlgoAPI_Common algo(impl_->shape, tool.impl_->shape);
                result = runBoolean(algo, "intersect");
                break;
            }
            default:
                throw KernelError(ErrorCode::InvalidArgument, "Unknown boolean operation");
        }
        auto impl = std::make_unique<Impl>();
        impl->shape = hasFaces(result) ? tidy(result) : result;
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::fillet(double radius, int face) const {
    require(std::isfinite(radius) && radius > kTiny, "Radius must be positive");
    return guarded("fillet", [&] {
        BRepFilletAPI_MakeFillet algo(impl_->shape);
        const auto faces = facesOf(impl_->shape);
        const TopoDS_Shape source = face < 0 ? impl_->shape : TopoDS_Shape(faceAt(faces, face));
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(source, TopAbs_EDGE, edges);
        for (int i = 1; i <= edges.Extent(); ++i) algo.Add(radius, TopoDS::Edge(edges(i)));
        algo.Build();
        if (!algo.IsDone()) throw KernelError(ErrorCode::OcctFailure, "Fillet failed: radius too large?");
        auto impl = std::make_unique<Impl>();
        impl->shape = algo.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::chamfer(double distance, int face) const {
    require(std::isfinite(distance) && distance > kTiny, "Distance must be positive");
    return guarded("chamfer", [&] {
        BRepFilletAPI_MakeChamfer algo(impl_->shape);
        const auto faces = facesOf(impl_->shape);
        const TopoDS_Shape source = face < 0 ? impl_->shape : TopoDS_Shape(faceAt(faces, face));
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(source, TopAbs_EDGE, edges);
        for (int i = 1; i <= edges.Extent(); ++i) algo.Add(distance, TopoDS::Edge(edges(i)));
        algo.Build();
        if (!algo.IsDone()) throw KernelError(ErrorCode::OcctFailure, "Chamfer failed: distance too large?");
        auto impl = std::make_unique<Impl>();
        impl->shape = algo.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::pushPull(int faceIndex, double distance) const {
    require(std::isfinite(distance) && std::abs(distance) > kTiny, "Distance must not be zero");
    return guarded("pushPull", [&] {
        const auto faces = facesOf(impl_->shape);
        const TopoDS_Face& face = faceAt(faces, faceIndex);
        BRepAdaptor_Surface surface(face);
        require(surface.GetType() == GeomAbs_Plane, "Only flat faces can be pushed or pulled");

        // Outward normal: the plane's normal, flipped for a reversed face.
        gp_Dir normal = surface.Plane().Axis().Direction();
        if (face.Orientation() == TopAbs_REVERSED) normal.Reverse();

        BRepPrimAPI_MakePrism prism(face, gp_Vec(normal) * distance);
        prism.Build();
        if (!prism.IsDone()) throw KernelError(ErrorCode::OcctFailure, "pushPull prism failed");

        TopoDS_Shape result;
        if (distance > 0) {
            BRepAlgoAPI_Fuse algo(impl_->shape, prism.Shape());
            result = runBoolean(algo, "pushPull add");
        } else {
            BRepAlgoAPI_Cut algo(impl_->shape, prism.Shape());
            result = runBoolean(algo, "pushPull cut");
        }
        auto impl = std::make_unique<Impl>();
        impl->shape = tidy(result);
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::compound(const std::vector<const Shape*>& shapes) {
    require(!shapes.empty(), "Nothing to combine");
    TopoDS_Compound compound;
    BRep_Builder builder;
    builder.MakeCompound(compound);
    for (const Shape* s : shapes) {
        require(s != nullptr, "Null shape");
        builder.Add(compound, s->impl_->shape);
    }
    auto impl = std::make_unique<Impl>();
    impl->shape = compound;
    return std::unique_ptr<Shape>(new Shape(std::move(impl)));
}

std::unique_ptr<Shape> Shape::loft(const std::vector<const Shape*>& profiles) {
    require(profiles.size() >= 2, "Loft needs at least two profiles");
    return guarded("loft", [&] {
        BRepOffsetAPI_ThruSections sections(Standard_True, Standard_False);
        for (const Shape* p : profiles) {
            require(p != nullptr, "Null shape");
            TopExp_Explorer face(p->impl_->shape, TopAbs_FACE);
            require(face.More(), "Profile has no face");
            sections.AddWire(BRepTools::OuterWire(TopoDS::Face(face.Current())));
        }
        sections.Build();
        if (!sections.IsDone()) throw KernelError(ErrorCode::OcctFailure, "loft failed");
        auto impl = std::make_unique<Impl>();
        impl->shape = sections.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::sweep(const SketchProfileInput& path) const {
    require(finite3(path.origin) && finite3(path.xAxis) && finite3(path.yAxis), "Plane must be finite");
    require(path.lines.size() % 4 == 0 && path.circles.empty(), "Path must be lines and arcs");
    return guarded("sweep", [&] {
        TopTools_ListOfShape edges = sketchEdges(path, planeNormal(path));
        require(!edges.IsEmpty(), "Path is empty");
        TopoDS_Compound all;
        BRep_Builder builder;
        builder.MakeCompound(all);
        for (const TopoDS_Shape& e : edges) builder.Add(all, e);
        TopoDS_Shape wires;
        BOPAlgo_Tools::EdgesToWires(all, wires, Standard_False);
        TopExp_Explorer wire(wires, TopAbs_WIRE);
        require(wire.More(), "Path is not connected");
        TopExp_Explorer face(impl_->shape, TopAbs_FACE);
        require(face.More(), "Profile has no face");
        BRepOffsetAPI_MakePipe pipe(TopoDS::Wire(wire.Current()), face.Current());
        pipe.Build();
        if (!pipe.IsDone()) throw KernelError(ErrorCode::OcctFailure, "sweep failed");
        auto impl = std::make_unique<Impl>();
        impl->shape = pipe.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

double Shape::distanceTo(const Shape& other) const {
    return guarded("distanceTo", [&] {
        BRepExtrema_DistShapeShape dist(impl_->shape, other.impl_->shape);
        if (!dist.IsDone()) throw KernelError(ErrorCode::OcctFailure, "distance failed");
        return dist.Value();
    });
}

namespace {

// Appends every edge of `edges` as a polyline of segments, reading x and y only.
void appendSegments(const TopoDS_Shape& edges, double deflection, std::vector<float>& out) {
    if (edges.IsNull()) return;
    for (TopExp_Explorer it(edges, TopAbs_EDGE); it.More(); it.Next()) {
        BRepAdaptor_Curve curve(TopoDS::Edge(it.Current()));
        GCPnts_QuasiUniformDeflection points(curve, deflection);
        if (!points.IsDone() || points.NbPoints() < 2) continue;
        for (int i = 1; i < points.NbPoints(); ++i) {
            const gp_Pnt a = points.Value(i);
            const gp_Pnt b = points.Value(i + 1);
            out.insert(out.end(), {static_cast<float>(a.X()), static_cast<float>(a.Y()),
                                   static_cast<float>(b.X()), static_cast<float>(b.Y())});
        }
    }
}

}  // namespace

ViewProjection Shape::project(const double toViewer[3], const double up[3], double deflection) const {
    require(finite3(toViewer) && finite3(up), "View directions must be finite");
    require(std::isfinite(deflection) && deflection > kTiny, "Deflection must be positive");
    const gp_Vec z(toViewer[0], toViewer[1], toViewer[2]);
    const gp_Vec u(up[0], up[1], up[2]);
    require(z.Magnitude() > kTiny && u.Magnitude() > kTiny, "View directions must not be zero");
    const gp_Vec x = u.Crossed(z);
    require(x.Magnitude() > kTiny, "Up must not be parallel to the view direction");
    return guarded("project", [&] {
        Handle(HLRBRep_Algo) hlr = new HLRBRep_Algo();
        hlr->Add(impl_->shape);
        hlr->Projector(HLRAlgo_Projector(gp_Ax2(gp_Pnt(0, 0, 0), gp_Dir(z), gp_Dir(x))));
        hlr->Update();
        hlr->Hide();
        HLRBRep_HLRToShape extract(hlr);
        ViewProjection out;
        appendSegments(extract.VCompound(), deflection, out.visible);
        appendSegments(extract.OutLineVCompound(), deflection, out.visible);
        appendSegments(extract.Rg1LineVCompound(), deflection, out.visible);
        appendSegments(extract.HCompound(), deflection, out.hidden);
        appendSegments(extract.OutLineHCompound(), deflection, out.hidden);
        return out;
    });
}

std::unique_ptr<Shape> Shape::shell(int face, double thickness) const {
    require(std::isfinite(thickness) && thickness > kTiny, "Wall thickness must be positive");
    return guarded("shell", [&] {
        const auto faces = facesOf(impl_->shape);
        TopTools_ListOfShape open;
        if (face >= 0) open.Append(faceAt(faces, face));
        BRepOffsetAPI_MakeThickSolid algo;
        // Negative offset: the wall grows inwards, keeping the outer size.
        algo.MakeThickSolidByJoin(impl_->shape, open, -thickness, 1e-3);
        algo.Build();
        if (!algo.IsDone()) throw KernelError(ErrorCode::OcctFailure, "Shell failed: wall too thick for this shape?");
        auto impl = std::make_unique<Impl>();
        impl->shape = algo.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

double Shape::faceArea(int face) const {
    return guarded("faceArea", [&] {
        const auto faces = facesOf(impl_->shape);
        GProp_GProps props;
        BRepGProp::SurfaceProperties(faceAt(faces, face), props);
        return props.Mass();
    });
}

std::unique_ptr<Shape> Shape::moved(const double axisOrigin[3], const double axisDirection[3], double angle,
                                    double dx, double dy, double dz) const {
    require(finite3(axisOrigin) && finite3(axisDirection) && std::isfinite(angle), "Motion must be finite");
    require(std::isfinite(dx) && std::isfinite(dy) && std::isfinite(dz), "Motion must be finite");
    const gp_Vec dir(axisDirection[0], axisDirection[1], axisDirection[2]);
    require(dir.Magnitude() > kTiny, "Axis direction must not be zero");
    return guarded("moved", [&] {
        gp_Trsf rotation;
        rotation.SetRotation(gp_Ax1(gp_Pnt(axisOrigin[0], axisOrigin[1], axisOrigin[2]), gp_Dir(dir)), angle);
        gp_Trsf translation;
        translation.SetTranslation(gp_Vec(dx, dy, dz));
        BRepBuilderAPI_Transform algo(impl_->shape, translation * rotation, Standard_True);
        auto impl = std::make_unique<Impl>();
        impl->shape = algo.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

std::unique_ptr<Shape> Shape::mirrored(const double origin[3], const double normal[3]) const {
    require(finite3(origin) && finite3(normal), "Plane must be finite");
    const gp_Vec n(normal[0], normal[1], normal[2]);
    require(n.Magnitude() > kTiny, "Plane normal must not be zero");
    return guarded("mirrored", [&] {
        gp_Trsf mirror;
        mirror.SetMirror(gp_Ax2(gp_Pnt(origin[0], origin[1], origin[2]), gp_Dir(n)));
        BRepBuilderAPI_Transform algo(impl_->shape, mirror, Standard_True);
        auto impl = std::make_unique<Impl>();
        impl->shape = algo.Shape();
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

int Shape::faceCount() const { return facesOf(impl_->shape).Extent(); }

double Shape::volume() const {
    return guarded("volume", [&] {
        GProp_GProps props;
        BRepGProp::VolumeProperties(impl_->shape, props);
        return std::abs(props.Mass());
    });
}

PickHit Shape::pick(const double origin[3], const double direction[3]) const {
    require(finite3(origin) && finite3(direction), "Ray must be finite");
    const gp_Vec dir(direction[0], direction[1], direction[2]);
    require(dir.Magnitude() > kTiny, "Ray direction must not be zero");
    return guarded("pick", [&]() -> PickHit {
        IntCurvesFace_ShapeIntersector intersector;
        intersector.Load(impl_->shape, 1e-6);
        const gp_Lin ray(gp_Pnt(origin[0], origin[1], origin[2]), gp_Dir(dir));
        intersector.PerformNearest(ray, 0.0, 1e12);
        if (!intersector.IsDone() || intersector.NbPnt() < 1) return {-1, 0.0};
        const auto faces = facesOf(impl_->shape);
        const int index = faces.FindIndex(intersector.Face(1));
        return {index - 1, intersector.WParameter(1)};
    });
}

namespace {
TopTools_IndexedMapOfShape edgesOf(const TopoDS_Shape& shape) {
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(shape, TopAbs_EDGE, edges);
    return edges;
}

const TopoDS_Edge& edgeAt(const TopTools_IndexedMapOfShape& edges, int index) {
    if (index < 0 || index >= edges.Extent()) throw KernelError(ErrorCode::InvalidArgument, "Edge index out of range");
    return TopoDS::Edge(edges(index + 1));
}
}  // namespace

std::unique_ptr<Shape> Shape::blendEdge(double size, int edge, bool chamfer) const {
    require(std::isfinite(size) && size > kTiny, "Size must be positive");
    return guarded("blendEdge", [&] {
        const auto edges = edgesOf(impl_->shape);
        const TopoDS_Edge& e = edgeAt(edges, edge);
        TopoDS_Shape result;
        if (chamfer) {
            BRepFilletAPI_MakeChamfer algo(impl_->shape);
            algo.Add(size, e);
            algo.Build();
            if (!algo.IsDone()) throw KernelError(ErrorCode::OcctFailure, "Chamfer failed: distance too large?");
            result = algo.Shape();
        } else {
            BRepFilletAPI_MakeFillet algo(impl_->shape);
            algo.Add(size, e);
            algo.Build();
            if (!algo.IsDone()) throw KernelError(ErrorCode::OcctFailure, "Fillet failed: radius too large?");
            result = algo.Shape();
        }
        auto impl = std::make_unique<Impl>();
        impl->shape = result;
        return std::unique_ptr<Shape>(new Shape(std::move(impl)));
    });
}

int Shape::pickEdge(const double origin[3], const double direction[3], double tolerance) const {
    require(finite3(origin) && finite3(direction), "Ray must be finite");
    const gp_Vec dir(direction[0], direction[1], direction[2]);
    require(dir.Magnitude() > kTiny, "Ray direction must not be zero");
    require(std::isfinite(tolerance) && tolerance > 0, "Tolerance must be positive");
    return guarded("pickEdge", [&]() -> int {
        const gp_Pnt o(origin[0], origin[1], origin[2]);
        const gp_Dir d(dir);
        const TopoDS_Edge ray = BRepBuilderAPI_MakeEdge(o, o.Translated(gp_Vec(d) * 1e6));
        const auto edges = edgesOf(impl_->shape);
        int best = -1;
        double bestAlong = 0, bestGap = 0;
        for (int i = 1; i <= edges.Extent(); ++i) {
            BRepExtrema_DistShapeShape dist(edges(i), ray);
            if (!dist.IsDone() || dist.NbSolution() < 1) continue;
            const double gap = dist.Value();
            if (gap > tolerance) continue;
            const double along = gp_Vec(o, dist.PointOnShape2(1)).Dot(gp_Vec(d));
            // Prefer the edge nearer the viewer; within a tolerance band the tighter fit wins.
            if (best < 0 || along < bestAlong - tolerance || (std::abs(along - bestAlong) <= tolerance && gap < bestGap)) {
                best = i - 1;
                bestAlong = along;
                bestGap = gap;
            }
        }
        return best;
    });
}

std::vector<double> Shape::edgePoints(int edge) const {
    return guarded("edgePoints", [&] {
        const auto edges = edgesOf(impl_->shape);
        BRepAdaptor_Curve curve(edgeAt(edges, edge));
        GCPnts_QuasiUniformDeflection points(curve, 0.05);
        std::vector<double> out;
        if (!points.IsDone()) return out;
        for (int i = 1; i <= points.NbPoints(); ++i) {
            const gp_Pnt p = points.Value(i);
            out.insert(out.end(), {p.X(), p.Y(), p.Z()});
        }
        return out;
    });
}

}  // namespace cad::kernel
