import unittest
from validate_model_ancestry_profile import validate,KINDS

def fixture():
    lines=[]
    for phase in ['outside_bake','bake']:
        for kind in sorted(KINDS):
            active=(phase,kind) in [('outside_bake','PARENTS'),('outside_bake','DEPENDENCIES'),('bake','MATERIAL'),('bake','TEXTURE_ENTRY')]
            n=1000 if active else 0;s=5 if active else 0
            fields=dict(phase=phase,kind=kind,calls=n,nested=0,samples=s,cpu_valid=s,alloc_valid=s,cpu_ns=500 if active else 0,wall_ns=900 if active else 0,allocated_bytes=100 if active else 0,failures=0,map_probes=2000 if kind=='TEXTURE_ENTRY' and active else 0,map_hits=1000 if kind=='TEXTURE_ENTRY' and active else 0,alias_checks=300 if kind=='MATERIAL' and active else 0,direct=700 if kind=='MATERIAL' and active else 0,aliased=300 if kind=='MATERIAL' and active else 0,already_linked=500 if kind=='PARENTS' and active else 0,cycle_sets=n if kind=='PARENTS' else 0,chain_lists=n if kind=='MATERIAL' else 0)
            lines.append('BOOTOPTIM_MODEL_ANCESTRY '+' '.join(str(k)+'='+str(v) for k,v in fields.items()))
    return '\n'.join(lines)+'\nBOOTOPTIM_MODEL_ANCESTRY_SUMMARY inflight=0 overflow=false'
class Contract(unittest.TestCase):
    def test_complete(self):self.assertTrue(validate(fixture())['valid'])
    def test_missing(self):
        with self.assertRaises(ValueError):validate('\n'.join(fixture().splitlines()[1:]))
    def test_unfinished(self):
        with self.assertRaises(ValueError):validate(fixture().replace('inflight=0','inflight=1'))
    def test_probe_inactive(self):
        with self.assertRaises(ValueError):validate(fixture().replace('map_probes=2000','map_probes=0').replace('map_hits=1000','map_hits=0'))
    def test_alloc_unsupported(self):
        with self.assertRaises(ValueError):validate(fixture().replace('alloc_valid=5','alloc_valid=0'))
    def test_material_incomplete(self):
        with self.assertRaises(ValueError):validate(fixture().replace('aliased=300','aliased=299'))

    def test_factory_missing(self):
        with self.assertRaises(ValueError):validate(fixture().replace('cycle_sets=1000','cycle_sets=0'))
