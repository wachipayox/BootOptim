"""Run independent comparisons against an exact Sodium class, not a reimplementation."""
import argparse, hashlib, io, pathlib, subprocess, tempfile, zipfile
p=argparse.ArgumentParser()
p.add_argument("--sodium-jar", required=True, type=pathlib.Path)
p.add_argument("--candidate-source-dir", required=True, type=pathlib.Path)
p.add_argument("--semantic-only", action="store_true")
a=p.parse_args()
EXPECTED="78b5648f4d4b1b76cc0eab4a7bc3df547288a28c2510e02eabc4a81a6dd3a6b6"
PREFIX="net/caffeinemc/mods/sodium/client/model/quad/"
def find(z):
    name=PREFIX+"properties/ModelQuadFlags.class"
    if name in z.namelist(): return z
    for n in z.namelist():
        if n.endswith(".jar"):
            result=find(zipfile.ZipFile(io.BytesIO(z.read(n))))
            if result is not None:return result
    return None
with zipfile.ZipFile(a.sodium_jar) as outer:
    z=find(outer)
    if z is None:raise SystemExit("Sodium classifier not found")
    digest=hashlib.sha256(z.read(PREFIX+"properties/ModelQuadFlags.class")).hexdigest()
    if digest!=EXPECTED:raise SystemExit("Unexpected baseline class SHA256: "+digest)
    with tempfile.TemporaryDirectory(prefix="bootoptim-sodium-quad-audit-") as tmp:
        root=pathlib.Path(tmp);classes=root/"classes";src=root/"src";classes.mkdir();src.mkdir()
        for n in (PREFIX+"properties/ModelQuadFlags.class",PREFIX+"properties/ModelQuadFlags$1.class",PREFIX+"ModelQuadView.class"):
            f=classes/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(z.read(n))
        direction=src/"net/minecraft/core/Direction.java";direction.parent.mkdir(parents=True,exist_ok=True)
        direction.write_text("package net.minecraft.core; public enum Direction { DOWN(Axis.Y), UP(Axis.Y), NORTH(Axis.Z), SOUTH(Axis.Z), WEST(Axis.X), EAST(Axis.X); private final Axis a; Direction(Axis a){this.a=a;} public Axis getAxis(){return a;} public enum Axis { X,Y,Z } }")
        sprite=src/"net/minecraft/client/renderer/texture/TextureAtlasSprite.java";sprite.parent.mkdir(parents=True,exist_ok=True)
        sprite.write_text("package net.minecraft.client.renderer.texture; public class TextureAtlasSprite {}")
        files=[a.candidate_source_dir/"SodiumQuadFlagClassifier.java",a.candidate_source_dir/"QuadCoordinateView.java"]
        for f in files:print("candidate_source_sha256",f.name,hashlib.sha256(f.read_bytes()).hexdigest(),flush=True)
        subprocess.run(["javac","-cp",str(classes),"-d",str(classes),str(direction),str(sprite),*[str(f) for f in files],str(pathlib.Path(__file__).with_name("Audit.java"))],check=True)
        subprocess.run(["java","-cp",str(classes),"Audit",*(["semantic-only"] if a.semantic_only else [])],check=True)
