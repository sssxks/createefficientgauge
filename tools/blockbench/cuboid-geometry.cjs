"use strict";

const FACE_NAMES = ["north", "east", "south", "west", "up", "down"];
const GEOMETRY_EPSILON = 1e-6;
const OCCLUSION_PROBE = 1e-4;

function add3(left, right) {
    return [
        left[0] + right[0],
        left[1] + right[1],
        left[2] + right[2],
    ];
}

function subtract3(left, right) {
    return [
        left[0] - right[0],
        left[1] - right[1],
        left[2] - right[2],
    ];
}

function scale3(vector, amount) {
    return [vector[0] * amount, vector[1] * amount, vector[2] * amount];
}

function dot3(left, right) {
    return (
        left[0] * right[0] +
        left[1] * right[1] +
        left[2] * right[2]
    );
}

function cross3(left, right) {
    return [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ];
}

function length3(vector) {
    return Math.sqrt(dot3(vector, vector));
}

function rotateOnAxis(point, axis, angle) {
    const cosine = Math.cos(angle);
    const sine = Math.sin(angle);
    if (axis === 0) {
        return [
            point[0],
            point[1] * cosine - point[2] * sine,
            point[1] * sine + point[2] * cosine,
        ];
    }
    if (axis === 1) {
        return [
            point[0] * cosine + point[2] * sine,
            point[1],
            -point[0] * sine + point[2] * cosine,
        ];
    }
    return [
        point[0] * cosine - point[1] * sine,
        point[0] * sine + point[1] * cosine,
        point[2],
    ];
}

function rotatePoint(point, rotation = [0, 0, 0], origin = [0, 0, 0]) {
    let rotated = subtract3(point, origin);
    for (let axis = 0; axis < 3; axis += 1) {
        if (rotation[axis]) {
            rotated = rotateOnAxis(
                rotated,
                axis,
                (rotation[axis] * Math.PI) / 180,
            );
        }
    }
    return add3(rotated, origin);
}

function unrotatePoint(point, rotation = [0, 0, 0], origin = [0, 0, 0]) {
    let unrotated = subtract3(point, origin);
    for (let axis = 2; axis >= 0; axis -= 1) {
        if (rotation[axis]) {
            unrotated = rotateOnAxis(
                unrotated,
                axis,
                (-rotation[axis] * Math.PI) / 180,
            );
        }
    }
    return add3(unrotated, origin);
}

function localFace(specification, faceName) {
    const [x0, y0, z0] = specification.from;
    const [x1, y1, z1] = specification.to;
    switch (faceName) {
        case "north":
            return {
                normal: [0, 0, -1],
                origin: [x0, y0, z0],
                u: [x1 - x0, 0, 0],
                v: [0, y1 - y0, 0],
            };
        case "south":
            return {
                normal: [0, 0, 1],
                origin: [x0, y0, z1],
                u: [x1 - x0, 0, 0],
                v: [0, y1 - y0, 0],
            };
        case "west":
            return {
                normal: [-1, 0, 0],
                origin: [x0, y0, z0],
                u: [0, 0, z1 - z0],
                v: [0, y1 - y0, 0],
            };
        case "east":
            return {
                normal: [1, 0, 0],
                origin: [x1, y0, z0],
                u: [0, 0, z1 - z0],
                v: [0, y1 - y0, 0],
            };
        case "down":
            return {
                normal: [0, -1, 0],
                origin: [x0, y0, z0],
                u: [x1 - x0, 0, 0],
                v: [0, 0, z1 - z0],
            };
        case "up":
            return {
                normal: [0, 1, 0],
                origin: [x0, y1, z0],
                u: [x1 - x0, 0, 0],
                v: [0, 0, z1 - z0],
            };
        default:
            throw new Error(`Unknown cube face: ${faceName}`);
    }
}

function describeFace(specification, cubeIndex, faceName) {
    const local = localFace(specification, faceName);
    const rotation = specification.rotation || [0, 0, 0];
    const pivot = specification.origin || [0, 0, 0];
    const origin = rotatePoint(local.origin, rotation, pivot);
    const u = subtract3(
        rotatePoint(add3(local.origin, local.u), rotation, pivot),
        origin,
    );
    const v = subtract3(
        rotatePoint(add3(local.origin, local.v), rotation, pivot),
        origin,
    );
    const rotatedNormalPoint = rotatePoint(
        add3(local.origin, local.normal),
        rotation,
        pivot,
    );
    const normalVector = subtract3(rotatedNormalPoint, origin);
    const normal = scale3(normalVector, 1 / length3(normalVector));
    return {
        cubeIndex,
        cubeName: specification.name,
        faceName,
        normal,
        origin,
        u,
        v,
        corners: [
            origin,
            add3(origin, u),
            add3(add3(origin, u), v),
            add3(origin, v),
        ],
    };
}

function describeGeometry(specifications) {
    return specifications.flatMap((specification, cubeIndex) =>
        FACE_NAMES.map((faceName) =>
            describeFace(specification, cubeIndex, faceName),
        ),
    );
}

function facePoint(face, u, v) {
    return add3(face.origin, add3(scale3(face.u, u), scale3(face.v, v)));
}

function faceCoordinates(face, point) {
    const relative = subtract3(point, face.origin);
    return [
        dot3(relative, face.u) / dot3(face.u, face.u),
        dot3(relative, face.v) / dot3(face.v, face.v),
    ];
}

function pointInsideCuboid(
    point,
    specification,
    epsilon = GEOMETRY_EPSILON,
) {
    const local = unrotatePoint(
        point,
        specification.rotation || [0, 0, 0],
        specification.origin || [0, 0, 0],
    );
    return local.every(
        (coordinate, axis) =>
            coordinate >= specification.from[axis] - epsilon &&
            coordinate <= specification.to[axis] + epsilon,
    );
}

// Java item models cannot cut a rectangular face into arbitrary visible
// pieces. Cull conservatively: only remove a complete face when all four
// corners, nudged outwards, are inside another convex cuboid.
function findOccludedFaces(
    specifications,
    faces = describeGeometry(specifications),
) {
    const occluded = [];
    for (const face of faces) {
        const probes = face.corners.map((corner) =>
            add3(corner, scale3(face.normal, OCCLUSION_PROBE)),
        );
        for (
            let cubeIndex = 0;
            cubeIndex < specifications.length;
            cubeIndex += 1
        ) {
            if (cubeIndex === face.cubeIndex) {
                continue;
            }
            if (
                probes.every((point) =>
                    pointInsideCuboid(
                        point,
                        specifications[cubeIndex],
                        GEOMETRY_EPSILON,
                    ),
                )
            ) {
                occluded.push({
                    cubeIndex: face.cubeIndex,
                    cubeName: face.cubeName,
                    faceName: face.faceName,
                    occluderIndex: cubeIndex,
                    occluderName: specifications[cubeIndex].name,
                });
                break;
            }
        }
    }
    return occluded;
}

function clipPolygonToUnitSquare(polygon) {
    const boundaries = [
        {
            inside: ([x]) => x >= -GEOMETRY_EPSILON,
            intersect: (left, right) => {
                const amount = -left[0] / (right[0] - left[0]);
                return [
                    0,
                    left[1] + (right[1] - left[1]) * amount,
                ];
            },
        },
        {
            inside: ([x]) => x <= 1 + GEOMETRY_EPSILON,
            intersect: (left, right) => {
                const amount = (1 - left[0]) / (right[0] - left[0]);
                return [
                    1,
                    left[1] + (right[1] - left[1]) * amount,
                ];
            },
        },
        {
            inside: ([, y]) => y >= -GEOMETRY_EPSILON,
            intersect: (left, right) => {
                const amount = -left[1] / (right[1] - left[1]);
                return [
                    left[0] + (right[0] - left[0]) * amount,
                    0,
                ];
            },
        },
        {
            inside: ([, y]) => y <= 1 + GEOMETRY_EPSILON,
            intersect: (left, right) => {
                const amount = (1 - left[1]) / (right[1] - left[1]);
                return [
                    left[0] + (right[0] - left[0]) * amount,
                    1,
                ];
            },
        },
    ];

    let clipped = polygon;
    for (const boundary of boundaries) {
        const input = clipped;
        clipped = [];
        for (let index = 0; index < input.length; index += 1) {
            const current = input[index];
            const previous =
                input[(index + input.length - 1) % input.length];
            const currentInside = boundary.inside(current);
            const previousInside = boundary.inside(previous);
            if (currentInside !== previousInside) {
                clipped.push(boundary.intersect(previous, current));
            }
            if (currentInside) {
                clipped.push(current);
            }
        }
        if (clipped.length === 0) {
            break;
        }
    }
    return clipped;
}

function polygonArea(polygon) {
    let doubledArea = 0;
    for (let index = 0; index < polygon.length; index += 1) {
        const current = polygon[index];
        const next = polygon[(index + 1) % polygon.length];
        doubledArea +=
            current[0] * next[1] - next[0] * current[1];
    }
    return Math.abs(doubledArea) / 2;
}

function findCoplanarOverlaps(faces) {
    const overlaps = [];
    for (let leftIndex = 0; leftIndex < faces.length; leftIndex += 1) {
        const left = faces[leftIndex];
        for (
            let rightIndex = leftIndex + 1;
            rightIndex < faces.length;
            rightIndex += 1
        ) {
            const right = faces[rightIndex];
            if (left.cubeIndex === right.cubeIndex) {
                continue;
            }
            const normalDot = dot3(left.normal, right.normal);
            if (Math.abs(normalDot) < 1 - GEOMETRY_EPSILON) {
                continue;
            }
            const planeDistance = Math.abs(
                dot3(
                    left.normal,
                    subtract3(right.origin, left.origin),
                ),
            );
            if (planeDistance > GEOMETRY_EPSILON) {
                continue;
            }
            const projected = right.corners.map((point) =>
                faceCoordinates(left, point),
            );
            const clipped = clipPolygonToUnitSquare(projected);
            const area =
                polygonArea(clipped) * length3(cross3(left.u, left.v));
            if (area <= GEOMETRY_EPSILON) {
                continue;
            }
            overlaps.push({
                area,
                direction: normalDot > 0 ? "same" : "opposite",
                left,
                right,
            });
        }
    }
    return overlaps;
}

module.exports = {
    FACE_NAMES,
    GEOMETRY_EPSILON,
    describeGeometry,
    faceCoordinates,
    facePoint,
    findCoplanarOverlaps,
    findOccludedFaces,
    pointInsideCuboid,
};
