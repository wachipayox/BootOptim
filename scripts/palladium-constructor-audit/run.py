"""Offline audit; no game launch, installation, or runtime patch."""
import argparse, hashlib, pathlib, subprocess, tempfile, zipfile

HERE = pathlib.Path(__file__).resolve().parent
PIN = '70083d3eaacec032a9ff066474e6df1e81d3e77125fa59f211252dcccec3997e'

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--palladium', required=True)
    p.add_argument('--guava', required=True)
    p.add_argument('--failureaccess', required=True)
    args = p.parse_args()
    jar = pathlib.Path(args.palladium)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != PIN:
        raise SystemExit('Unsupported Palladium binary; refusing audit')
    with tempfile.TemporaryDirectory(prefix='palladium-offline-') as temporary:
        root = pathlib.Path(temporary)
        with zipfile.ZipFile(jar) as archive:
            for name in ['com/mr_toad/palladium/core/mixin/ModelResourceLocationMixin.class',
                         'com/mr_toad/palladium/client/model/ModelResourceLocationProperties.class',
                         'com/mr_toad/palladium/common/Deduplicator.class']:
                dest = root / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(archive.read(name))
        cp = str(root) + ';' + args.guava + ';' + args.failureaccess
        subprocess.run(['javac', '-cp', cp, '-d', str(root), *map(str, HERE.rglob('*.java'))], check=True)
        subprocess.run(['java', '-cp', cp, 'Audit'], check=True)

if __name__ == '__main__':
    main()
