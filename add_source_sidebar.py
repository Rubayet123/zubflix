import sys

with open('app/src/main/res/layout/activity_player.xml', 'r') as f:
    content = f.read()

source_sidebar = """    <!-- Sources Sidebar -->
    <androidx.constraintlayout.widget.ConstraintLayout
        android:id="@+id/sourceSidebarContainer"
        android:layout_width="380dp"
        android:layout_height="match_parent"
        android:background="#F0141414"
        android:elevation="12dp"
        android:visibility="gone"
        android:clickable="true"
        android:focusable="true"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <!-- Header -->
        <TextView
            android:id="@+id/tv_source_sidebar_title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Sources"
            android:textColor="#FFFFFF"
            android:textSize="18sp"
            android:textStyle="bold"
            android:layout_marginStart="24dp"
            android:layout_marginTop="24dp"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintStart_toStartOf="parent"/>

        <ImageButton
            android:id="@+id/btn_close_source_sidebar"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="16dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@android:drawable/ic_menu_close_clear_cancel"
            app:tint="#FFFFFF"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintEnd_toEndOf="parent"/>

        <!-- Sources List Layout -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_marginTop="16dp"
            android:orientation="vertical"
            android:paddingHorizontal="12dp"
            app:layout_constraintTop_toBottomOf="@id/tv_source_sidebar_title"
            app:layout_constraintBottom_toBottomOf="parent">

            <androidx.core.widget.NestedScrollView
                android:layout_width="match_parent"
                android:layout_height="match_parent">

                <LinearLayout
                    android:id="@+id/sourcesContainer"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"/>
            </androidx.core.widget.NestedScrollView>
        </LinearLayout>
    </androidx.constraintlayout.widget.ConstraintLayout>

</androidx.constraintlayout.widget.ConstraintLayout>"""

content = content.replace("</androidx.constraintlayout.widget.ConstraintLayout>", source_sidebar)

with open('app/src/main/res/layout/activity_player.xml', 'w') as f:
    f.write(content)
