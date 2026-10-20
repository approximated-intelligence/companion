import numpy as np
d = np.fromfile('geoid.bin', dtype='<f4')       # little-endian, matches Android nativeOrder()
assert d.size == 1441 * 721, d.size
np.round(d * 10).astype('<i2').tofile('geoid_v2.bin')   # dm, int16
