#!/usr/bin/env python3
"""
Test script for processing school.db and generating preview images
"""

import sys
import os

# Add the path_service directory to the path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from services.extraction import extract_trajectory_from_db, get_trajectory_stats
from services.deduplication import deduplicate_path, merge_overlapping_segments
from services.path_flattening import snap_to_lines
from services.vertical_detector import (
    detect_stairs_first,
    separate_floors,
    assign_floors_to_stairs
)
from services.visualization import generate_preview_images

import numpy as np

def main():
    db_path = "../db/school.db"
    output_dir = "./output"

    os.makedirs(output_dir, exist_ok=True)

    print("=" * 60)
    print("School.db Path Processing Test")
    print("=" * 60)

    # Step 1: Extract trajectory
    print("\n[1/6] Extracting trajectory from database...")
    try:
        positions, node_ids = extract_trajectory_from_db(db_path)
        stats = get_trajectory_stats(positions)
        print(f"  - Total nodes: {stats['total_nodes']}")
        print(f"  - X range: {stats['x_range'][0]:.3f} ~ {stats['x_range'][1]:.3f} m")
        print(f"  - Y range: {stats['y_range'][0]:.3f} ~ {stats['y_range'][1]:.3f} m")
        print(f"  - Z range: {stats['z_range'][0]:.3f} ~ {stats['z_range'][1]:.3f} m")
        print(f"  - Total distance: {stats['total_distance']:.2f} m")
    except Exception as e:
        print(f"  ERROR: {e}")
        import traceback
        traceback.print_exc()
        return

    # Step 2: Detect stairs FIRST (before floor separation)
    print("\n[2/6] Detecting stairs/elevators...")
    try:
        stair_segments, stair_mask = detect_stairs_first(positions)
        stair_points = np.sum(stair_mask)
        print(f"  - Detected {len(stair_segments)} vertical passage(s)")
        print(f"  - Stair points: {stair_points} ({100*stair_points/len(positions):.1f}%)")
        for i, seg in enumerate(stair_segments):
            z_change = seg['z_end'] - seg['z_start']
            direction = "UP" if z_change > 0 else "DOWN"
            print(f"    - {seg['type']} #{i+1}: Z {seg['z_start']:.1f}m -> {seg['z_end']:.1f}m "
                  f"({direction} {abs(z_change):.1f}m, {seg['end_idx']-seg['start_idx']} pts)")
    except Exception as e:
        print(f"  ERROR: {e}")
        import traceback
        traceback.print_exc()
        stair_segments = []
        stair_mask = np.zeros(len(positions), dtype=bool)

    # Step 3: Separate floors (excluding stair points)
    print("\n[3/6] Separating floors (excluding stair points)...")
    try:
        floors_data = separate_floors(positions, node_ids, stair_mask=stair_mask)
        print(f"  - Detected {len(floors_data)} floor(s)")
        for level, data in sorted(floors_data.items()):
            floor_name = f"{level}F" if level >= 0 else f"B{abs(level)}"
            print(f"    - {floor_name}: {data['point_count']} points, "
                  f"Z={data['z_min']:.2f}~{data['z_max']:.2f}m")
    except Exception as e:
        print(f"  ERROR: {e}")
        import traceback
        traceback.print_exc()
        # Fallback: treat all non-stair points as one floor
        floor_mask = ~stair_mask
        floors_data = {0: {
            'positions': positions[floor_mask],
            'node_ids': [node_ids[i] for i in range(len(node_ids)) if floor_mask[i]]
        }}

    # Step 3.5: Assign floor numbers to stairs
    print("\n[3.5/6] Assigning floor connections to stairs...")
    try:
        stair_segments = assign_floors_to_stairs(stair_segments, floors_data)
        for i, seg in enumerate(stair_segments):
            print(f"    - {seg['type']} #{i+1}: {seg['from_floor']}F -> {seg['to_floor']}F")
    except Exception as e:
        print(f"  ERROR: {e}")

    # Step 4: Deduplicate paths (two-stage: merge overlapping, then deduplicate)
    print("\n[4/6] Removing duplicate path segments...")
    deduplicated_floors = {}
    for floor_level, floor_data in floors_data.items():
        original_count = len(floor_data['positions'])
        # Stage 1: Merge overlapping back-and-forth segments
        merged = merge_overlapping_segments(floor_data['positions'], overlap_threshold=1.0)
        # Stage 2: Deduplicate with larger threshold for floor paths
        deduplicated = deduplicate_path(merged, distance_threshold=0.5)
        deduplicated_floors[floor_level] = deduplicated
        print(f"  - Floor {floor_level}: {original_count} -> {len(merged)} -> {len(deduplicated)} points "
              f"({100 * len(deduplicated) / original_count:.1f}% kept)")

    # Step 5: RDP + 직선 스냅 (스무딩 + 직선화를 한 번에)
    print("\n[5/6] Snapping paths to straight lines (RDP + line snap)...")
    straightened_floors = {}
    for floor_level, floor_positions in deduplicated_floors.items():
        snapped = snap_to_lines(floor_positions, epsilon=0.5, point_spacing=0.5)
        straightened_floors[floor_level] = snapped
        print(f"  - Floor {floor_level}: {len(floor_positions)} -> {len(snapped)} points")

    # Step 6: Generate preview images
    print("\n[6/6] Generating preview images...")
    output_prefix = os.path.join(output_dir, "school")
    try:
        paths = generate_preview_images(
            positions,
            straightened_floors,
            stair_segments,
            output_prefix
        )
        print("  Generated images:")
        for img_type, img_path in paths.items():
            print(f"    - {img_type}: {img_path}")
    except Exception as e:
        print(f"  ERROR: {e}")
        import traceback
        traceback.print_exc()
        return

    # Summary
    print("\n" + "=" * 60)
    print("Processing Complete!")
    print("=" * 60)

    total_processed_points = sum(len(p) for p in straightened_floors.values())
    total_processed_distance = 0
    for floor_positions in straightened_floors.values():
        floor_positions = np.array(floor_positions)
        dist = np.sqrt(np.sum(np.diff(floor_positions, axis=0)**2, axis=1))
        total_processed_distance += np.sum(dist)

    print(f"\nOriginal: {len(positions)} points, {stats['total_distance']:.2f}m")
    print(f"Processed (floors only): {total_processed_points} points, {total_processed_distance:.2f}m")
    print(f"Floors: {len(straightened_floors)}")
    print(f"Vertical passages (stairs/elevators): {len(stair_segments)}")
    print(f"\nOutput files in: {os.path.abspath(output_dir)}")


if __name__ == "__main__":
    main()
