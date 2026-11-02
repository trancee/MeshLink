import ActivityKit
import SwiftUI
import WidgetKit

@main
struct LabWidgets: WidgetBundle {
    var body: some Widget {
        LabLiveActivity()
    }
}

struct LabLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LabActivityAttributes.self) { context in
            Text("MeshLink lab \(context.attributes.run): \(context.state.status)").padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { Text(context.state.status) }
            } compactLeading: {
                Text("ML")
            } compactTrailing: {
                Text("")
            } minimal: {
                Text("ML")
            }
        }
    }
}
