// The full-frame pass-through for `OpaqueWaterEffect`. Character for character what every
// engine effect's vertex shader is (`pulseengine/shaders/effects/*.vert` are all this file),
// because `FullFrameRenderer` — which `BaseEffect` builds for us — binds exactly two vertex
// attributes by NAME, `position` and `texCoord`, and draws one screen-covering triangle pair
// in normalised device coordinates.
//
// It is a COPY rather than a reuse of the engine's, and deliberately so: this file lives in
// `/shaders/` and shadows nothing, so it can neither be caught by `build.gradle.kts`'s
// `devOnlyShaderOverrides` exclusion (which drops our two copies of engine shaders from the
// release jar) nor accidentally shadow an engine file on the classpath. Loading the engine's
// path from our effect would work today and would silently break the day the engine renames
// it. See `IridescenceRenderer.VERTEX_SHADER` for the same argument.
//
// #version 330 core, not 150: macOS caps OpenGL at 4.1 and 330 is the highest the whole
// project can share. See `docs/superpowers/specs/2026-08-11-outstanding-work.md` §3.7.
#version 330 core

in vec2 position;
in vec2 texCoord;

out vec2 uv;

void main()
{
    uv = texCoord;
    gl_Position = vec4(position, 0.0, 1.0);
}
