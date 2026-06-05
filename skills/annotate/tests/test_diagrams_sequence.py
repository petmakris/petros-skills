"""Sequence-diagram validator + renderer tests."""
import re

import pytest

from skills.annotate.diagrams.sequence import ValidationError, validate, render


def _minimal_spec():
    return {
        "actors": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}],
        "steps": [{"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "hi"}],
    }


def test_validate_minimal_spec_ok():
    validate(_minimal_spec())  # no raise


def test_validate_requires_two_actors():
    spec = _minimal_spec()
    spec["actors"] = [{"id": "a", "label": "A"}]
    with pytest.raises(ValidationError, match="actors"):
        validate(spec)


def test_validate_requires_at_least_one_step():
    spec = _minimal_spec()
    spec["steps"] = []
    with pytest.raises(ValidationError, match="step"):
        validate(spec)


def test_validate_unknown_from_actor():
    spec = _minimal_spec()
    spec["steps"][0]["from"] = "ghost"
    with pytest.raises(ValidationError, match="from"):
        validate(spec)


def test_validate_unknown_to_actor():
    spec = _minimal_spec()
    spec["steps"][0]["to"] = "ghost"
    with pytest.raises(ValidationError, match="to"):
        validate(spec)


def test_validate_self_arrow_requires_same_actor():
    spec = _minimal_spec()
    spec["steps"][0] = {"id": "s1", "from": "a", "to": "b", "arrow": "self", "label": "x"}
    with pytest.raises(ValidationError, match="self"):
        validate(spec)


def test_validate_self_arrow_with_same_actor_ok():
    spec = _minimal_spec()
    spec["steps"][0] = {"id": "s1", "from": "a", "to": "a", "arrow": "self", "label": "loop"}
    validate(spec)


def test_validate_unknown_arrow_type():
    spec = _minimal_spec()
    spec["steps"][0]["arrow"] = "magic"
    with pytest.raises(ValidationError, match="arrow"):
        validate(spec)


def test_validate_duplicate_step_ids():
    spec = _minimal_spec()
    spec["steps"].append(
        {"id": "s1", "from": "b", "to": "a", "arrow": "request", "label": "dup"}
    )
    with pytest.raises(ValidationError, match="duplicate"):
        validate(spec)


def test_validate_phase_start_at_unknown_step():
    spec = _minimal_spec()
    spec["phases"] = [{"id": "p1", "label": "P", "start_at": "s99"}]
    with pytest.raises(ValidationError, match="start_at"):
        validate(spec)


def test_validate_phase_order_must_follow_step_order():
    spec = _minimal_spec()
    spec["steps"].append({"id": "s2", "from": "b", "to": "a", "arrow": "request", "label": "y"})
    spec["phases"] = [
        {"id": "p1", "label": "P1", "start_at": "s2"},
        {"id": "p2", "label": "P2", "start_at": "s1"},  # out of order
    ]
    with pytest.raises(ValidationError, match="phase.*order"):
        validate(spec)


def test_validate_phases_optional():
    spec = _minimal_spec()
    # No "phases" key — should still validate.
    validate(spec)


def _spec_3actors():
    return {
        "actors": [
            {"id": "a", "label": "Alpha"},
            {"id": "b", "label": "Beta"},
            {"id": "c", "label": "Gamma"},
        ],
        "steps": [
            {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "ping"},
        ],
    }


def test_render_returns_svg_element():
    svg = render(_spec_3actors(), block_id="b-0")
    assert svg.startswith("<svg")
    assert svg.rstrip().endswith("</svg>")
    assert 'data-block-id="b-0"' in svg


def test_render_emits_one_actor_pill_per_actor():
    svg = render(_spec_3actors(), block_id="b-0")
    # Each actor renders as a <rect class="actor-pill"> + <text class="actor-label">.
    assert svg.count('class="actor-pill"') == 3
    assert ">Alpha<" in svg
    assert ">Beta<" in svg
    assert ">Gamma<" in svg


def test_render_emits_lifeline_per_actor():
    svg = render(_spec_3actors(), block_id="b-0")
    assert svg.count('class="lane"') == 3


def test_render_escapes_actor_label_html():
    spec = _spec_3actors()
    spec["actors"][0]["label"] = "<script>x</script>"
    svg = render(spec, block_id="b-0")
    assert "<script>x</script>" not in svg
    assert "&lt;script&gt;" in svg


def _spec_one_of_each_arrow():
    return {
        "actors": [
            {"id": "a", "label": "A"},
            {"id": "b", "label": "B"},
        ],
        "steps": [
            {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "req", "sub": "subreq"},
            {"id": "s2", "from": "b", "to": "a", "arrow": "event",   "label": "evt"},
            {"id": "s3", "from": "a", "to": "a", "arrow": "self",    "label": "loop"},
        ],
    }


def test_render_emits_step_row_per_step():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert svg.count('class="step-row"') == 3


def test_render_includes_data_step_id_per_row():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    for sid in ("s1", "s2", "s3"):
        assert f'data-step-id="{sid}"' in svg


def test_render_arrow_class_per_type():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert 'class="arr-request"' in svg
    assert 'class="arr-event"' in svg
    assert 'class="arr-self"' in svg


def test_render_self_loop_is_path_not_line():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    # Self loops use <path d="..."> ; cross-actor uses <line>.
    assert re.search(r'<path[^>]*class="arr-self"', svg) is not None
    assert re.search(r'<line[^>]*class="arr-request"', svg) is not None


def test_render_emits_step_label_and_sub():
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert ">req<" in svg
    assert ">subreq<" in svg
    assert ">evt<" in svg


def test_render_step_id_present_as_g_element():
    """Each step is grouped under a <g class='step-row' data-step-id='sN'>."""
    svg = render(_spec_one_of_each_arrow(), block_id="b-0")
    assert re.search(r'<g class="step-row" data-block-id="b-0" data-step-id="s1">', svg) is not None


def test_render_escapes_step_label_html():
    spec = _spec_one_of_each_arrow()
    spec["steps"][0]["label"] = "<b>oops</b>"
    svg = render(spec, block_id="b-0")
    assert "<b>oops</b>" not in svg
    assert "&lt;b&gt;oops&lt;/b&gt;" in svg


def _spec_with_phases():
    return {
        "actors": [
            {"id": "a", "label": "A"},
            {"id": "b", "label": "B"},
        ],
        "phases": [
            {"id": "p1", "label": "FIRST",  "start_at": "s1"},
            {"id": "p2", "label": "SECOND", "start_at": "s3"},
        ],
        "steps": [
            {"id": "s1", "from": "a", "to": "b", "arrow": "request", "label": "x"},
            {"id": "s2", "from": "b", "to": "a", "arrow": "request", "label": "y"},
            {"id": "s3", "from": "a", "to": "b", "arrow": "request", "label": "z"},
        ],
    }


def test_render_emits_phase_label_per_phase():
    svg = render(_spec_with_phases(), block_id="b-0")
    assert ">FIRST<" in svg
    assert ">SECOND<" in svg
    assert svg.count('class="phase-label"') == 2


def test_render_emits_phase_band_rect_per_phase():
    svg = render(_spec_with_phases(), block_id="b-0")
    assert svg.count('class="phase-band"') == 2


def test_render_omits_phases_when_absent():
    spec = _spec_with_phases()
    del spec["phases"]
    svg = render(spec, block_id="b-0")
    assert 'class="phase-band"' not in svg
    assert 'class="phase-label"' not in svg


def test_validate_requires_actor_label():
    """An actor without a label would crash render() with a bare KeyError;
    validate() must reject it as a clean ValidationError instead."""
    spec = _minimal_spec()
    del spec["actors"][0]["label"]
    with pytest.raises(ValidationError, match="label"):
        validate(spec)


def test_validate_requires_phase_label():
    spec = _minimal_spec()
    spec["phases"] = [{"id": "p1", "start_at": "s1"}]
    with pytest.raises(ValidationError, match="label"):
        validate(spec)


def test_render_labelless_actor_raises_validation_not_keyerror():
    """render() runs validate() first, so a labelless actor surfaces as a
    ValidationError (which the server turns into an error pill), never an
    unhandled KeyError that would blank the page."""
    spec = _minimal_spec()
    del spec["actors"][0]["label"]
    with pytest.raises(ValidationError):
        render(spec, block_id="b-0")
