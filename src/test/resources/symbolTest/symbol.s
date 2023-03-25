labelDef: ;comment comment
  .res 16
lda     #labelDef:
sta     (labelDef), y

.struct camera
    lookAt .res 2
    eye .res 2
    up .res 2
.endstruct

iwt r1, #(camera_mem + camera::eye + vector3::xPos)
ibt r3, #.sizeof(edgerow)

bob = 2
bob