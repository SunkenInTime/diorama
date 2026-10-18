"""Tests for modCore.png: the dependency-free PNG writer.

The files are read back with an independent chunk parser so the tests check the
bytes on disk, not just the writer's return value.
"""

import os
import shutil
import struct
import tempfile
import unittest
import zlib

from _core import load

png = load("png")


def read_chunks(data):
    """Independent PNG chunk walker; asserts each CRC."""
    assert data[:8] == png.PNG_SIGNATURE
    position = 8
    chunks = []
    while position < len(data):
        length = struct.unpack(">I", data[position : position + 4])[0]
        tag = data[position + 4 : position + 8]
        payload = data[position + 8 : position + 8 + length]
        crc = struct.unpack(">I", data[position + 8 + length : position + 12 + length])[0]
        assert crc == zlib.crc32(tag + payload) & 0xFFFFFFFF
        chunks.append((tag, payload))
        position += 12 + length
    return chunks


def pixels_of(data, width, height):
    """Decodes an 8-bit RGBA, filter-0 PNG into a list of rows of rgba tuples."""
    idat = b"".join(payload for tag, payload in read_chunks(data) if tag == b"IDAT")
    raw = zlib.decompress(idat)
    rows = []
    stride = 1 + width * 4
    for y in range(height):
        line = raw[y * stride : (y + 1) * stride]
        assert line[0] == 0
        row = []
        for x in range(width):
            base = 1 + x * 4
            row.append(tuple(line[base : base + 4]))
        rows.append(row)
    return rows


class WritePngTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix="diorama-png-")

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def path(self, name="a.png"):
        return os.path.join(self.directory, name)

    def test_structure_and_pixels(self):
        target = self.path()
        rows = [[(1, 2, 3, 4), (5, 6, 7, 8)], [(9, 10, 11, 12), (13, 14, 15, 16)]]
        self.assertEqual(png.write_png(target, 2, 2, rows), target)
        data = open(target, "rb").read()
        chunks = read_chunks(data)
        self.assertEqual(chunks[0][0], b"IHDR")
        width, height, depth, colour_type = struct.unpack(">IIBB", chunks[0][1][:10])
        self.assertEqual((width, height, depth, colour_type), (2, 2, 8, 6))
        self.assertEqual(chunks[-1][0], b"IEND")
        self.assertEqual(pixels_of(data, 2, 2), rows)

    def test_packed_rgba_ints(self):
        target = self.path()
        png.write_png(target, 1, 1, [[0x11223344]])
        self.assertEqual(pixels_of(open(target, "rb").read(), 1, 1), [[(0x11, 0x22, 0x33, 0x44)]])

    def test_three_tuple_defaults_to_opaque(self):
        target = self.path()
        png.write_png(target, 1, 1, [[(200, 100, 50)]])
        self.assertEqual(pixels_of(open(target, "rb").read(), 1, 1), [[(200, 100, 50, 255)]])

    def test_channels_are_clamped(self):
        target = self.path()
        png.write_png(target, 1, 1, [[(300, -5, 255)]])
        self.assertEqual(pixels_of(open(target, "rb").read(), 1, 1), [[(255, 0, 255, 255)]])

    def test_extra_rows_are_ignored(self):
        target = self.path()
        png.write_png(target, 1, 1, [[(1, 1, 1, 255)], [(2, 2, 2, 255)]])
        self.assertEqual(pixels_of(open(target, "rb").read(), 1, 1), [[(1, 1, 1, 255)]])

    def test_too_few_rows_raise(self):
        with self.assertRaises(ValueError):
            png.write_png(self.path(), 2, 2, [[(0, 0, 0)]])

    def test_empty_image_raises(self):
        with self.assertRaises(ValueError):
            png.write_png(self.path(), 0, 4, [])
        with self.assertRaises(ValueError):
            png.write_png(self.path(), 4, 0, [])


class HelperTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix="diorama-png-")

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_write_indexed_square(self):
        target = os.path.join(self.directory, "square.png")
        png.write_indexed_square(target, 3, lambda x, y, size: (x * 10, y * 10, 0))
        rows = pixels_of(open(target, "rb").read(), 3, 3)
        self.assertEqual(rows[0][0], (0, 0, 0, 255))
        self.assertEqual(rows[2][2], (20, 20, 0, 255))

    def test_abgr_rows_reorders_to_rgba(self):
        rows = png.abgr_rows([0xAABBCCDD], 1)
        self.assertEqual(rows, [[(0xDD, 0xCC, 0xBB, 0xAA)]])

    def test_abgr_rows_covers_the_whole_buffer(self):
        rows = png.abgr_rows([0xFF000000, 0xFF0000FF, 0xFF00FF00, 0xFFFF0000], 2)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0][0], (0, 0, 0, 255))
        self.assertEqual(rows[0][1], (255, 0, 0, 255))
        self.assertEqual(rows[1][0], (0, 255, 0, 255))
        self.assertEqual(rows[1][1], (0, 0, 255, 255))


if __name__ == "__main__":
    unittest.main()
