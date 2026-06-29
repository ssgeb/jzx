from kafka_detection_worker import build_defect_evidence, build_statistics


def test_single_image_statistics_expose_remote_category_and_confidence():
    statistics = build_statistics(
        [{
            "fileName": "door.jpg",
            "detections": [
                {"label": "Bent", "score": 0.73, "box": [10, 20, 60, 80]},
                {"label": "Rusty", "score": 0.91, "box": [15, 25, 70, 95]},
            ],
        }],
        failed_images=0,
    )

    assert statistics["singleImageResult"] == {
        "category": "Rusty",
        "confidence": 0.91,
    }


def test_defect_evidence_accepts_worker_score_and_list_box():
    evidence = build_defect_evidence(
        [{
            "fileName": "door.jpg",
            "sourceKey": "source/door.jpg",
            "previewKey": "result/door.jpg",
            "detections": [
                {"label": "Bent", "score": 0.73, "box": [10, 20, 60, 80]},
            ],
        }]
    )

    assert evidence[0]["confidence"] == 0.73
    assert evidence[0]["area"] == 3000.0
