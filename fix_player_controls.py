import sys

with open('app/src/main/res/layout/custom_player_control_view.xml', 'r') as f:
    content = f.read()

source_btn = """            <!-- Sources Option -->
            <LinearLayout
                android:id="@+id/btn_sources"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center"
                android:background="?attr/selectableItemBackground"
                android:paddingVertical="8dp"
                android:paddingHorizontal="16dp"
                android:layout_marginStart="12dp"
                android:clickable="true"
                android:focusable="true">
                <ImageView
                    android:layout_width="20dp"
                    android:layout_height="20dp"
                    android:src="@drawable/ic_search"
                    app:tint="#FFFFFF"/>
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:text="Sources"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold"/>
            </LinearLayout>

            <!-- Aspect Ratio Option -->"""

content = content.replace("            <!-- Aspect Ratio Option -->", source_btn)

with open('app/src/main/res/layout/custom_player_control_view.xml', 'w') as f:
    f.write(content)
