with open('app/src/main/res/layout/activity_player.xml', 'r') as f:
    content = f.read()

# content ends around "SUBTITLES" because of previous grep, wait no, content was truncated at the first `</androidx.constraintlayout.widget.ConstraintLayout>` which is the root!
